package com.polyzones.core.index;

import com.polyzones.core.geo.GeometryTolerance;
import com.polyzones.core.zone.Zone;

/** Strategy seam for choosing an exact point-query index when a snapshot is built. */
@FunctionalInterface
public interface ZoneIndexFactory {
    ZoneIndex create(Zone[] zones, GeometryTolerance tolerance, int nodeCapacity);
}
