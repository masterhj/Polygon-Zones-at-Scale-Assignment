package com.polyzones.core.trip;

import com.polyzones.core.geo.LonLat;
import java.time.Instant;
import java.util.UUID;

/** Validated producer ping; the pipeline applies tenant, age and future-skew policy. */
public record Ping(UUID tripId, long seq, Instant eventTime, LonLat location, Double accuracyMeters) {
    public Ping {
        if (tripId == null || seq < 0 || eventTime == null || location == null
                || (accuracyMeters != null && (!Double.isFinite(accuracyMeters) || accuracyMeters < 0)))
            throw new IllegalArgumentException("Invalid ping");
    }
}
