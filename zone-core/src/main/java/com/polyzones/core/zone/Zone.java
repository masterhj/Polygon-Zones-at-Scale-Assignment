package com.polyzones.core.zone;

import com.polyzones.core.shape.PolygonShape;

/** Minimal immutable versioned zone value needed by the spatial snapshot. */
public record Zone(ZoneKey id, int version, ZoneType type, int priority, PolygonShape shape) {
    public Zone {
        if (id == null || version < 1 || type == null || shape == null) throw new IllegalArgumentException("Invalid zone metadata");
    }
}
