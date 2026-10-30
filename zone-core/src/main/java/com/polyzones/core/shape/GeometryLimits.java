package com.polyzones.core.shape;

/** Canonical polygon bounds from D-04 and D-05. */
public final class GeometryLimits {
    public static final int MAX_VERTICES = 50_000;
    public static final int MAX_HOLES = 256;
    public static final double MAX_REGION_METERS = 1_000_000;
    public static final double MAX_ABS_LATITUDE = 89.9;
    private GeometryLimits() { }
}
