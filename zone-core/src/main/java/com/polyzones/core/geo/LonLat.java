package com.polyzones.core.geo;

/** A longitude/latitude coordinate in decimal degrees. */
public record LonLat(double lon, double lat) {
    public LonLat {
        if (!Double.isFinite(lon) || !Double.isFinite(lat)) throw new IllegalArgumentException("Coordinates must be finite");
    }
    public static LonLat of(double lon, double lat) { return new LonLat(lon, lat); }
}
