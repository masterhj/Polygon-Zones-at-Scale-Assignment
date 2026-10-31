package com.polyzones.core.trip;

import java.util.Set;

/** One open parameter interval with constant polygon membership. -1 denotes the UNZONED billing bucket. */
public record SubSegment(double t0, double t1, int billingZoneOrdinal, Set<Integer> memberships, double meters) {
    public SubSegment {
        if (!Double.isFinite(t0) || !Double.isFinite(t1) || t0 < 0 || t1 > 1 || t0 >= t1
                || billingZoneOrdinal < -1 || !Double.isFinite(meters) || meters < 0) throw new IllegalArgumentException("Invalid sub-segment");
        memberships = Set.copyOf(memberships);
    }
}
