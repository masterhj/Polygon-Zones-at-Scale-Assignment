package com.polyzones.core.trip;

import com.polyzones.core.zone.ZoneKey;
import java.time.Instant;
import java.util.UUID;

public record ZoneEvent(UUID eventId, UUID tenantId, UUID tripId, ZoneKey zoneId, int zoneVersion,
                        long zoneVersionSetVersion, long evaluatedSetVersion, long transitionOrdinal,
                        TransitionKind kind, Instant eventTime, Double dwellSeconds, EventReason reason) {
    public ZoneEvent {
        if (eventId == null || tenantId == null || tripId == null || zoneId == null || zoneVersion < 1
                || zoneVersionSetVersion < 0 || evaluatedSetVersion < 0 || transitionOrdinal < 1
                || kind == null || eventTime == null || reason == null
                || (dwellSeconds != null && (!Double.isFinite(dwellSeconds) || dwellSeconds < 0)))
            throw new IllegalArgumentException("Invalid zone event");
        if (kind == TransitionKind.ENTER && dwellSeconds != null) throw new IllegalArgumentException("ENTER cannot have dwell time");
    }
}
