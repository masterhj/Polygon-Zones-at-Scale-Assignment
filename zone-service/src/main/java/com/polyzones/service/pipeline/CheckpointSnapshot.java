package com.polyzones.service.pipeline;

import com.polyzones.core.trip.AlertCandidate;
import com.polyzones.core.trip.Ping;
import com.polyzones.core.trip.OpenMembership;
import com.polyzones.core.trip.SkippedRange;
import com.polyzones.core.trip.TripAttributor;
import com.polyzones.core.trip.TripState;
import com.polyzones.core.trip.TripStateCheckpoint;
import com.polyzones.core.trip.ZoneEvent;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.polyzones.core.geo.LonLat;
import com.polyzones.core.zone.ZoneKey;

/** Shard-consistent immutable persistence unit; totals are cumulative assignments, never deltas. */
public record CheckpointSnapshot(UUID tenantId, UUID tripId, long processedSeq, long durableAckedSeq,
                                 List<SkippedRange> skippedRanges, Map<String, Double> metersByZone,
                                 double totalMeters, long pinnedSetVersion, LonLat lastPoint, Instant lastEventTime,
                                 long nextTransitionOrdinal, Map<ZoneKey, OpenMembership> billingMemberships,
                                 Map<ZoneKey, OpenMembership> noGoMemberships, TripStateCheckpoint stateCheckpoint,
                                 List<ZoneEvent> events,
                                 List<AlertCandidate> alerts, List<Ping> replayablePings) {
    public CheckpointSnapshot {
        skippedRanges = List.copyOf(skippedRanges);
        metersByZone = Map.copyOf(metersByZone);
        billingMemberships = Map.copyOf(billingMemberships);
        noGoMemberships = Map.copyOf(noGoMemberships);
        events = List.copyOf(events);
        alerts = List.copyOf(alerts);
        replayablePings = List.copyOf(replayablePings);
    }

    static CheckpointSnapshot capture(TripState state, List<ZoneEvent> events,
                                      List<AlertCandidate> alerts, List<Ping> replayable) {
        // This is the frontier the atomic transaction is being asked to commit. The mutable state is
        // advanced only after commit returns successfully.
        return new CheckpointSnapshot(state.tenantId(), state.tripId(), state.processedSeq(), state.processedSeq(),
                state.skippedRanges(), state.distanceMeters(), state.totalMeters(), state.pinnedSetVersion(),
                state.lastPoint(), state.lastEventTime(), state.nextTransitionOrdinal(), state.billingMemberships(), state.noGoMemberships(),
                withDurableFrontier(state.checkpoint(), state.processedSeq()), events, alerts, replayable);
    }

    private static TripStateCheckpoint withDurableFrontier(TripStateCheckpoint checkpoint, long durableFrontier) {
        return new TripStateCheckpoint(checkpoint.tenantId(), checkpoint.tripId(), checkpoint.pinnedSetVersion(),
                checkpoint.lastPoint(), checkpoint.lastEventTime(), checkpoint.processedSeq(), durableFrontier,
                checkpoint.nextTransitionOrdinal(), checkpoint.gapsSkipped(), checkpoint.latePings(),
                checkpoint.timeRegressions(), checkpoint.suspectJumps(), checkpoint.totalMeters(),
                checkpoint.lastSafetySetVersion(), checkpoint.skippedRanges(), checkpoint.distanceMeters(),
                checkpoint.billingMemberships(), checkpoint.noGoMemberships(), checkpoint.completed());
    }
}
