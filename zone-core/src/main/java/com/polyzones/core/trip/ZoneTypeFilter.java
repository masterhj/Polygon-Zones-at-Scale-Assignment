package com.polyzones.core.trip;

import com.polyzones.core.zone.Zone;

@FunctionalInterface
public interface ZoneTypeFilter {
    boolean includes(Zone zone);
}
