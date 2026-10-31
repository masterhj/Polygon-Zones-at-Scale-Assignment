package com.polyzones.service.application;

import com.polyzones.core.trip.Ping;
import com.polyzones.core.trip.RoundingAllocator;
import com.polyzones.service.pipeline.PingIngestService;
import com.polyzones.service.publish.SnapshotRegistry;
import com.polyzones.service.security.TenantContext;
import com.polyzones.service.web.dto.PingAdmissionResponse;
import com.polyzones.service.web.dto.PingRequest;
import com.polyzones.service.web.dto.TripAttributionResponse;
import com.polyzones.service.web.dto.TripEventResponse;
import com.polyzones.service.web.dto.TripResponse;
import com.polyzones.service.web.dto.TripStartRequest;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.security.access.prepost.PreAuthorize;

@Service
public class TripService {
    private final JdbcTemplate jdbc;
    private final SnapshotRegistry snapshots;
    private final TripExecutionRegistry executions;
    private final PingIngestService ingest;
    private final Clock clock;
    private final TripMapper mapper;
    private final TransactionTemplate transactions;

    public TripService(JdbcTemplate jdbc, SnapshotRegistry snapshots, TripExecutionRegistry executions,
                       PingIngestService ingest, Clock clock, TripMapper mapper, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc; this.snapshots = snapshots; this.executions = executions; this.ingest = ingest;
        this.clock = clock; this.mapper = mapper; this.transactions = new TransactionTemplate(transactionManager);
    }

    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
    public TripResponse start(TripStartRequest request) {
        UUID tenant = TenantContext.requireTenantId();
        UUID tripId = request.tripId() == null ? UUID.randomUUID() : request.tripId();
        Long version;
        try { version = snapshots.latest(tenant).setVersion(); }
        catch (IllegalStateException missing) { throw new ApplicationException("ZONE_SET_UNAVAILABLE", "No published zone set is available for this tenant."); }
        try {
            transactions.executeWithoutResult(status -> {
                Integer employeeExists = jdbc.query("select 1 from app_users where tenant_id=? and id=? and role='EMPLOYEE' and enabled=true",
                        rs -> rs.next() ? 1 : null, tenant, request.employeeId());
                if (employeeExists == null) throw new ApplicationException("EMPLOYEE_NOT_FOUND", "The requested employee was not found.");
                jdbc.update("insert into trips(tenant_id,trip_id,employee_id,pinned_set_version,status,started_at) values (?,?,?,?,'IN_PROGRESS',?)",
                        tenant, tripId, request.employeeId(), version, Timestamp.from(request.startTime()));
            });
        } catch (org.springframework.dao.DuplicateKeyException conflict) {
            throw new ApplicationException("CONFLICT", "A trip with this identifier already exists.");
        }
        executions.register(tenant, tripId, version);
        return get(tenant, tripId);
    }

    @PreAuthorize("hasRole('OPERATOR')")
    public PingAdmissionResponse pings(UUID tripId, PingRequest request) {
        UUID tenant = TenantContext.requireTenantId();
        return executions.serialized(() -> {
            executions.processor(tenant, tripId);
            List<Ping> pings = request.pings().stream().map(item -> mapper.toPing(tripId, item)).toList();
            var items = new PingAdmissionResponse.Item[pings.size()];
            var candidates = new java.util.ArrayList<Ping>();
            var candidateIndexes = new java.util.ArrayList<Integer>();
            for (int index = 0; index < pings.size(); index++) {
                Ping ping = pings.get(index);
                if (ingest.validateTime(ping) == PingIngestService.Status.REJECTED_CLOCK_SKEW) {
                    items[index] = new PingAdmissionResponse.Item(ping.seq(), "REJECTED", "CLOCK_SKEW");
                    continue;
                }
                switch (executions.reserveSequence(tenant, tripId, ping.seq())) {
                    case ACCEPT -> { candidates.add(ping); candidateIndexes.add(index); }
                    case DUPLICATE -> items[index] = new PingAdmissionResponse.Item(ping.seq(), "DUPLICATE", null);
                    case LATE -> items[index] = new PingAdmissionResponse.Item(ping.seq(), "REJECTED", "LATE");
                    case INVALID -> items[index] = new PingAdmissionResponse.Item(ping.seq(), "REJECTED", "INVALID");
                }
            }
            try {
                if (!candidates.isEmpty()) {
                    PingIngestService.Admission result = ingest.ingest(tenant, tripId, candidates);
                    for (int index = 0; index < result.items().size(); index++) {
                        PingIngestService.ItemResult admission = result.items().get(index);
                        int inputIndex = candidateIndexes.get(index);
                        if (admission.status() == PingIngestService.Status.ACCEPTED) {
                            items[inputIndex] = new PingAdmissionResponse.Item(admission.seq(), "ACCEPTED", null);
                        } else {
                            executions.releaseSequence(tenant, tripId, admission.seq());
                            items[inputIndex] = new PingAdmissionResponse.Item(admission.seq(), "REJECTED",
                                    admission.status() == PingIngestService.Status.REJECTED_CLOCK_SKEW ? "CLOCK_SKEW" : "INVALID");
                        }
                    }
                }
            } catch (RuntimeException failure) {
                candidates.forEach(ping -> executions.releaseSequence(tenant, tripId, ping.seq()));
                throw failure;
            }
            return new PingAdmissionResponse(java.util.List.of(items), executions.processor(tenant, tripId).durableAckedSeq());
        });
    }

    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
    public TripResponse complete(UUID tripId) {
        UUID tenant = TenantContext.requireTenantId();
        return executions.serialized(() -> {
            executions.processor(tenant, tripId);
            ingest.flushTrip(tenant, tripId, java.time.Duration.ofSeconds(30));
            executions.complete(tenant, tripId, clock.instant());
            return get(tenant, tripId);
        });
    }

    @PreAuthorize("hasAnyRole('EMPLOYEE','OPERATOR','ADMIN')")
    public TripResponse get(UUID tripId) {
        TripResponse trip = get(TenantContext.requireTenantId(), tripId);
        requireEmployeeOwnership(trip);
        return trip;
    }

    @PreAuthorize("hasAnyRole('EMPLOYEE','OPERATOR','ADMIN')")
    public TripAttributionResponse attribution(UUID tripId) {
        UUID tenant = TenantContext.requireTenantId();
        TripResponse trip = get(tenant, tripId);
        requireEmployeeOwnership(trip);
        Map<String, Double> meters = jdbc.query("select zone_id,meters from trip_zone_distance where tenant_id=? and trip_id=?",
                rs -> { Map<String, Double> result = new java.util.TreeMap<>(); while (rs.next()) result.put(rs.getObject(1, UUID.class).toString(), rs.getDouble(2)); return result; }, tenant, tripId);
        Double unzoned = jdbc.query("select unzoned_meters from trips where tenant_id=? and trip_id=?",
                rs -> rs.next() ? rs.getDouble(1) : null, tenant, tripId);
        meters.put("UNZONED", unzoned == null ? 0d : unzoned);
        double total = meters.values().stream().mapToDouble(Double::doubleValue).sum();
        var allocation = RoundingAllocator.allocate(meters);
        return new TripAttributionResponse(meters, total, allocation.kilometersByKey(), allocation.roundedTotalKilometers(), "HAVERSINE_SEGMENT_SPLIT", trip.pinnedSetVersion());
    }

    @PreAuthorize("hasAnyRole('EMPLOYEE','OPERATOR','ADMIN')")
    public List<TripEventResponse> events(UUID tripId, int page, int size) {
        validatePage(page, size);
        UUID tenant = TenantContext.requireTenantId();
        requireEmployeeOwnership(get(tenant, tripId));
        return jdbc.query("select event_id,zone_id,zone_version,zone_version_set_version,evaluated_set_version,transition_ordinal,kind,event_time,dwell_seconds,reason from zone_events where tenant_id=? and trip_id=? order by transition_ordinal limit ? offset ?",
                (rs, row) -> new TripEventResponse(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getInt(3), rs.getLong(4),
                        rs.getLong(5), rs.getLong(6), rs.getString(7), rs.getTimestamp(8).toInstant(),
                        (Double) rs.getObject(9), rs.getString(10)), tenant, tripId, size, (long) page * size);
    }

    TripResponse get(UUID tenant, UUID tripId) {
        TripResponse response = jdbc.query("select trip_id,employee_id,pinned_set_version,status,started_at,completed_at,durable_acked_seq from trips where tenant_id=? and trip_id=?",
                rs -> rs.next() ? mapper.toResponse(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getLong(3),
                        rs.getString(4), rs.getTimestamp(5).toInstant(), rs.getTimestamp(6) == null ? null : rs.getTimestamp(6).toInstant(), rs.getLong(7)) : null,
                tenant, tripId);
        if (response == null) throw new ApplicationException("TRIP_NOT_FOUND", "The requested trip was not found.");
        return response;
    }

    private static void validatePage(int page, int size) { if (page < 0 || size < 1 || size > 200) throw new ApplicationException("VALIDATION_FAILED", "Pagination must use a non-negative page and size from 1 to 200."); }
    private static void requireEmployeeOwnership(TripResponse trip) {
        if (TenantContext.hasRole("EMPLOYEE") && !trip.employeeId().equals(TenantContext.currentUserId()))
            throw new ApplicationException("FORBIDDEN", "You are not permitted to read this trip.");
    }
}
