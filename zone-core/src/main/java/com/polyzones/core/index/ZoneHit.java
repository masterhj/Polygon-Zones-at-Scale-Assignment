package com.polyzones.core.index;

import com.polyzones.core.geo.Location;
import com.polyzones.core.zone.ZoneKey;
import com.polyzones.core.zone.ZoneType;

public record ZoneHit(ZoneKey id, int version, ZoneType type, int priority, Location location) { }
