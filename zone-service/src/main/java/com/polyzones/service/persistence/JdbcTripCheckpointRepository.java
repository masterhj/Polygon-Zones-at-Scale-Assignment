package com.polyzones.service.persistence;

import com.polyzones.core.index.ZoneSnapshot;
import com.polyzones.core.trip.AlertCandidate;
import com.polyzones.core.trip.ZoneEvent;
import com.polyzones.core.trip.TripStateCheckpoint;
import com.polyzones.core.zone.Zone;
import com.polyzones.core.zone.ZoneKey;
import com.polyzones.core.zone.ZoneType;
import com.polyzones.service.pipeline.CheckpointRepository;
import com.polyzones.service.pipeline.CheckpointSnapshot;
import com.polyzones.service.publish.PinnedSnapshotRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Atomic PostgreSQL checkpoint writer. Cumulative values are assigned and stale frontiers are rejected. */
public final class JdbcTripCheckpointRepository implements CheckpointRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final CheckpointStateCodec codec;
    private final PinnedSnapshotRegistry snapshots;
    private final Clock clock;

    public JdbcTripCheckpointRepository(JdbcTemplate jdbc, PlatformTransactionManager manager,
                                        CheckpointStateCodec codec, PinnedSnapshotRegistry snapshots, Clock clock) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
        this.codec = codec;
        this.snapshots = snapshots;
        this.clock = clock;
    }

    @Override public void commit(CheckpointSnapshot checkpoint) {
        transactions.executeWithoutResult(status -> commitTransaction(checkpoint));
    }

    @Override public TripStateCheckpoint loadState(UUID tenantId, UUID tripId) {
        if (tenantId == null || tripId == null) throw new IllegalArgumentException("Tenant and trip are required");
        String json = jdbc.query("select checkpoint::text from trips where tenant_id=? and trip_id=?",
                rs -> rs.next() ? rs.getString(1) : null, tenantId, tripId);
        return json == null ? null : codec.decode(json);
    }

    private void commitTransaction(CheckpointSnapshot checkpoint) {
        UUID tenant = checkpoint.tenantId(), trip = checkpoint.tripId();
        Long durable = jdbc.query("select durable_acked_seq from trips where tenant_id=? and trip_id=? for update",
                rs -> rs.next() ? rs.getLong(1) : null, tenant, trip);
        if (durable == null) throw new IllegalArgumentException("Trip checkpoint target not found");
        if (checkpoint.processedSeq() < durable) throw new StaleTripCheckpointException();
        TripIdentity identity = jdbc.query("select pinned_set_version from trips where tenant_id=? and trip_id=?",
                rs -> rs.next() ? new TripIdentity(rs.getLong(1)) : null, tenant, trip);
        if (identity == null || identity.pinnedSetVersion() != checkpoint.pinnedSetVersion())
            throw new IllegalArgumentException("Trip checkpoint version mismatch");

        ZoneSnapshot pinned = snapshots.acquire(tenant, checkpoint.pinnedSetVersion());
        try {
            for (var entry : checkpoint.metersByZone().entrySet()) {
                if ("UNZONED".equals(entry.getKey())) continue;
                UUID zoneUuid = UUID.fromString(entry.getKey());
                Integer ordinal = pinned.ordinalById().get(new ZoneKey(zoneUuid));
                if (ordinal == null) throw new IllegalStateException("Billing zone is absent from the pinned snapshot");
                Zone zone = pinned.zoneAt(ordinal);
                if (zone.type() == ZoneType.NO_GO) throw new IllegalArgumentException("NO_GO zone cannot receive billing distance");
                long sourceSetVersion = sourceSetVersion(tenant, zone, checkpoint.pinnedSetVersion());
                jdbc.update("""
                        insert into trip_zone_distance(tenant_id,trip_id,zone_id,zone_version,zone_version_set_version,pinned_set_version,meters)
                        values (?,?,?,?,?,?,?)
                        on conflict (tenant_id,trip_id,zone_id) do update set zone_version=excluded.zone_version,
                          zone_version_set_version=excluded.zone_version_set_version,pinned_set_version=excluded.pinned_set_version,
                          meters=excluded.meters
                        """, tenant, trip, zoneUuid, zone.version(), sourceSetVersion, checkpoint.pinnedSetVersion(), entry.getValue());
            }
            double unzoned = checkpoint.metersByZone().getOrDefault("UNZONED", 0d);
            int updated = jdbc.update("""
                    update trips set processed_seq=?,durable_acked_seq=?,checkpoint=?::jsonb,checkpoint_at=?,
                      unzoned_meters=?,next_transition_ordinal=?,
                      status=case when ? then 'COMPLETED' else status end,
                      completed_at=case when ? then ? else completed_at end
                    where tenant_id=? and trip_id=? and durable_acked_seq<=?
                    """, checkpoint.processedSeq(), checkpoint.durableAckedSeq(), codec.encode(checkpoint.stateCheckpoint()),
                    Timestamp.from(clock.instant()), unzoned, checkpoint.nextTransitionOrdinal(),
                    checkpoint.stateCheckpoint().completed(), checkpoint.stateCheckpoint().completed(), Timestamp.from(clock.instant()), tenant, trip,
                    checkpoint.durableAckedSeq());
            if (updated != 1) throw new StaleTripCheckpointException();
            for (ZoneEvent event : checkpoint.events()) insertEvent(event);
            for (AlertCandidate alert : checkpoint.alerts()) insertAlert(alert);
        } finally {
            snapshots.release(tenant, checkpoint.pinnedSetVersion());
        }
    }

    private long sourceSetVersion(UUID tenant, Zone zone, long pinnedSetVersion) {
        Long source = jdbc.query("""
                select zv.set_version from zone_versions zv
                join zone_set_versions sets on sets.tenant_id=zv.tenant_id and sets.version=zv.set_version
                where zv.tenant_id=? and zv.zone_id=? and zv.version=? and zv.set_version<=? and sets.status='PUBLISHED'
                """, rs -> rs.next() ? rs.getLong(1) : null, tenant, zone.id().value(), zone.version(), pinnedSetVersion);
        if (source == null) throw new IllegalStateException("Billing zone version is not in published history");
        return source;
    }

    private void insertEvent(ZoneEvent event) {
        jdbc.update("""
                insert into zone_events(event_id,tenant_id,trip_id,zone_id,zone_version,zone_version_set_version,
                  evaluated_set_version,transition_ordinal,kind,event_time,dwell_seconds,reason)
                values (?,?,?,?,?,?,?,?,?,?,?,?) on conflict (tenant_id,event_id) do nothing
                """, event.eventId(), event.tenantId(), event.tripId(), event.zoneId().value(), event.zoneVersion(),
                event.zoneVersionSetVersion(), event.evaluatedSetVersion(), event.transitionOrdinal(), event.kind().name(),
                Timestamp.from(event.eventTime()), event.dwellSeconds(), event.reason().name());
    }

    private void insertAlert(AlertCandidate alert) {
        jdbc.update("""
                insert into alerts(alert_id,tenant_id,trip_id,zone_id,source_event_id,kind,severity,event_time,
                  zone_version,zone_version_set_version,evaluated_set_version)
                select ?,e.tenant_id,e.trip_id,e.zone_id,e.event_id,?,'HIGH',e.event_time,e.zone_version,
                  e.zone_version_set_version,e.evaluated_set_version
                  from zone_events e where e.tenant_id=? and e.event_id=? and e.zone_id=?
                on conflict (tenant_id,alert_id) do nothing
                """, alert.alertId(), alert.kind(), alert.tenantId(), alert.sourceEventId(), alert.zoneId().value());
    }

    private record TripIdentity(long pinnedSetVersion) { }
    public static final class StaleTripCheckpointException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public StaleTripCheckpointException() { super("Trip checkpoint frontier is stale"); }
    }
}
