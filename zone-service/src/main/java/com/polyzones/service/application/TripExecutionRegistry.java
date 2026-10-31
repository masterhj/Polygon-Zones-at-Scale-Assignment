package com.polyzones.service.application;

import com.polyzones.core.index.ZoneSnapshot;
import com.polyzones.core.trip.NightWindow;
import com.polyzones.core.trip.TripAttributor;
import com.polyzones.core.trip.TripState;
import com.polyzones.service.observability.PipelineMetrics;
import com.polyzones.service.persistence.JdbcTripCheckpointRepository;
import com.polyzones.service.pipeline.ReorderBuffer;
import com.polyzones.service.pipeline.TripCheckpointWriter;
import com.polyzones.service.pipeline.TripProcessor;
import com.polyzones.service.publish.PinnedSnapshotRegistry;
import com.polyzones.service.publish.SnapshotRegistry;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Owns one single-writer processor and pinned snapshot reference for each active trip. */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public final class TripExecutionRegistry implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(TripExecutionRegistry.class);
    private final ConcurrentHashMap<Key, RuntimeTrip> trips = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Key, java.util.Set<Long>> admittedSequences = new ConcurrentHashMap<>();
    private final JdbcTemplate jdbc;
    private final JdbcTripCheckpointRepository checkpoints;
    private final SnapshotRegistry latest;
    private final PinnedSnapshotRegistry pinned;
    private final Clock clock;
    private final PipelineMetrics metrics;
    private final int reorderCapacity;
    private final Duration reorderHold;
    private final int checkpointEveryPings;
    private final Duration checkpointEvery;

    public TripExecutionRegistry(JdbcTemplate jdbc, JdbcTripCheckpointRepository checkpoints,
            SnapshotRegistry latest, PinnedSnapshotRegistry pinned, Clock clock, PipelineMetrics metrics,
            @Value("${zones.pipeline.reorder-max-buffered:64}") int reorderCapacity,
            @Value("${zones.pipeline.reorder-hold-ms:2000}") long reorderHoldMillis,
            @Value("${zones.pipeline.checkpoint-every-pings:200}") int checkpointEveryPings,
            @Value("${zones.pipeline.checkpoint-every-ms:2000}") long checkpointEveryMillis) {
        this.jdbc = jdbc; this.checkpoints = checkpoints; this.latest = latest; this.pinned = pinned;
        this.clock = clock; this.metrics = metrics; this.reorderCapacity = reorderCapacity;
        this.reorderHold = Duration.ofMillis(reorderHoldMillis); this.checkpointEveryPings = checkpointEveryPings;
        this.checkpointEvery = Duration.ofMillis(checkpointEveryMillis);
    }

    @Override public void run(ApplicationArguments args) {
        List<TripRow> active = jdbc.query("select tenant_id,trip_id,pinned_set_version from trips where status='IN_PROGRESS' order by tenant_id,trip_id",
                (rs, row) -> new TripRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getLong(3)));
        for (TripRow trip : active) register(trip.tenantId(), trip.tripId(), trip.pinnedSetVersion());
        log.info("Recovered in-progress trips count={}", active.size());
    }

    public synchronized void register(UUID tenantId, UUID tripId, long pinnedVersion) {
        Key key = new Key(tenantId, tripId);
        if (trips.containsKey(key)) return;
        ZoneSnapshot snapshot = pinned.acquire(tenantId, pinnedVersion);
        try {
            var saved = checkpoints.loadState(tenantId, tripId);
            TripState state = saved == null ? new TripState(tenantId, tripId, pinnedVersion) : TripState.restore(saved);
            TenantRules rules = jdbc.query("select timezone,night_start,night_end from tenants where id=?",
                    rs -> rs.next() ? new TenantRules(ZoneId.of(rs.getString(1)), rs.getTime(2).toLocalTime(), rs.getTime(3).toLocalTime()) : null,
                    tenantId);
            if (rules == null) throw new ApplicationException("NOT_FOUND", "The requested tenant was not found.");
            ReorderBuffer reorder = new ReorderBuffer(Math.max(0, state.processedSeq() + 1), reorderCapacity,
                    reorderHold, clock, state.skippedRanges());
            TripAttributor attributor = state.processedSeq() < 0 && state.lastPoint() == null
                    && state.lastEventTime() == null && state.distanceMeters().isEmpty()
                    && state.billingMemberships().isEmpty() && state.noGoMemberships().isEmpty()
                    ? new TripAttributor(tenantId, tripId, snapshot) : new TripAttributor(snapshot, state);
            if (attributor.state() != state) state = attributor.state();
            TripProcessor processor = new TripProcessor(attributor, state, reorder, () -> latest.latest(tenantId),
                    new NightWindow(rules.nightStart(), rules.nightEnd()), rules.timeZone(), clock,
                    new TripCheckpointWriter(checkpoints), checkpointEveryPings, checkpointEvery, metrics);
            trips.put(key, new RuntimeTrip(processor, pinnedVersion));
        } catch (RuntimeException failure) {
            pinned.release(tenantId, pinnedVersion);
            throw failure;
        } catch (Exception failure) {
            pinned.release(tenantId, pinnedVersion);
            throw new IllegalStateException("Trip recovery failed", failure);
        }
    }

    public TripProcessor processor(UUID tenantId, UUID tripId) {
        RuntimeTrip trip = trips.get(new Key(tenantId, tripId));
        if (trip == null) throw new ApplicationException("TRIP_NOT_FOUND", "The requested trip was not found.");
        return trip.processor();
    }

    public synchronized TripProcessor.SequenceDecision reserveSequence(UUID tenantId, UUID tripId, long seq) {
        TripProcessor processor = processor(tenantId, tripId);
        Key key = new Key(tenantId, tripId);
        java.util.Set<Long> pending = admittedSequences.computeIfAbsent(key, ignored -> ConcurrentHashMap.newKeySet());
        if (pending.contains(seq)) { processor.recordDuplicate(); return TripProcessor.SequenceDecision.DUPLICATE; }
        TripProcessor.SequenceDecision decision = processor.classifySequence(seq);
        if (decision == TripProcessor.SequenceDecision.ACCEPT) pending.add(seq);
        else if (decision == TripProcessor.SequenceDecision.DUPLICATE) processor.recordDuplicate();
        else if (decision == TripProcessor.SequenceDecision.LATE) processor.recordLate();
        return decision;
    }

    public void releaseSequence(UUID tenantId, UUID tripId, long seq) {
        java.util.Set<Long> pending = admittedSequences.get(new Key(tenantId, tripId));
        if (pending != null) pending.remove(seq);
    }

    public void process(UUID tenantId, com.polyzones.core.trip.Ping ping) {
        TripProcessor processor = processor(tenantId, ping.tripId());
        processor.accept(ping);
        releaseSequence(tenantId, ping.tripId(), ping.seq());
    }

    public synchronized TripProcessor complete(UUID tenantId, UUID tripId, java.time.Instant completedAt) {
        Key key = new Key(tenantId, tripId);
        RuntimeTrip runtime = trips.get(key);
        if (runtime == null) throw new ApplicationException("TRIP_NOT_FOUND", "The requested trip was not found.");
        runtime.processor().complete(completedAt);
        trips.remove(key);
        admittedSequences.remove(key);
        pinned.release(tenantId, runtime.pinnedVersion());
        return runtime.processor();
    }

    /** Serializes admission and completion control operations; shard workers remain independently ordered. */
    public synchronized <T> T serialized(Supplier<T> operation) { return operation.get(); }

    public boolean active(UUID tenantId, UUID tripId) { return trips.containsKey(new Key(tenantId, tripId)); }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString = "${zones.pipeline.tick-ms:100}")
    public void tick() {
        trips.forEach((key, trip) -> {
            try { trip.processor().tick(); }
            catch (TripProcessor.CheckpointBacklogException ignored) { /* retained state is retried on the next tick */ }
            catch (RuntimeException failure) { log.warn("Trip processing tick failed tenantId={} tripId={}", key.tenantId(), key.tripId()); }
        });
    }

    @PreDestroy public synchronized void close() {
        trips.forEach((key, trip) -> pinned.release(key.tenantId(), trip.pinnedVersion()));
        trips.clear();
        admittedSequences.clear();
    }

    private record Key(UUID tenantId, UUID tripId) { }
    private record TripRow(UUID tenantId, UUID tripId, long pinnedSetVersion) { }
    private record RuntimeTrip(TripProcessor processor, long pinnedVersion) { }
    private record TenantRules(ZoneId timeZone, java.time.LocalTime nightStart, java.time.LocalTime nightEnd) { }
}
