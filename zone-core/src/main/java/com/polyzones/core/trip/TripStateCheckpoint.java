package com.polyzones.core.trip;

import com.polyzones.core.geo.LonLat;
import com.polyzones.core.zone.ZoneKey;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Complete immutable shard checkpoint used to restore deterministic trip processing. */
public record TripStateCheckpoint(UUID tenantId, UUID tripId, long pinnedSetVersion, LonLat lastPoint,
                                  Instant lastEventTime, long processedSeq, long durableAckedSeq,
                                  long nextTransitionOrdinal, long gapsSkipped, long latePings,
                                  long timeRegressions, long suspectJumps, double totalMeters,
                                  long lastSafetySetVersion, List<SkippedRange> skippedRanges,
                                  Map<String, Double> distanceMeters,
                                  Map<ZoneKey, OpenMembership> billingMemberships,
                                  Map<ZoneKey, OpenMembership> noGoMemberships, boolean completed) {
    public TripStateCheckpoint {
        skippedRanges = List.copyOf(skippedRanges);
        distanceMeters = Map.copyOf(distanceMeters);
        billingMemberships = Map.copyOf(billingMemberships);
        noGoMemberships = Map.copyOf(noGoMemberships);
    }
}
