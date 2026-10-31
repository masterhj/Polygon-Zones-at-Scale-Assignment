package com.polyzones.core.trip;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;

/** Local-time night interval with inclusive start and exclusive end (D-07). */
public record NightWindow(LocalTime start, LocalTime end) {
    public NightWindow {
        if (start == null || end == null || start.equals(end)) throw new IllegalArgumentException("Night window must have distinct endpoints");
    }
    public boolean contains(Instant eventTime, ZoneId tenantZone) {
        if (eventTime == null || tenantZone == null) throw new IllegalArgumentException("Event time and tenant time zone are required");
        LocalTime local = eventTime.atZone(tenantZone).toLocalTime();
        return start.isBefore(end) ? !local.isBefore(start) && local.isBefore(end)
                : !local.isBefore(start) || local.isBefore(end);
    }
}
