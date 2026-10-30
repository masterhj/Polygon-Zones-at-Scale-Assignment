package com.polyzones.core.geo;

/** Spherical distance and linear interpolation in longitude/latitude degree space. */
public final class Haversine {
    /** Mean Earth radius from D-04, metres. */
    public static final double EARTH_RADIUS_METERS = 6_371_008.8;
    private Haversine() { }
    public static double distanceMeters(LonLat a, LonLat b) {
        double lat1 = Math.toRadians(a.lat()), lat2 = Math.toRadians(b.lat());
        double dLat = lat2 - lat1, dLon = Math.toRadians(b.lon() - a.lon());
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        h = Math.max(0, Math.min(1, h));
        return 2 * EARTH_RADIUS_METERS * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h));
    }
    public static LonLat lerp(LonLat a, LonLat b, double t) {
        if (!Double.isFinite(t)) throw new IllegalArgumentException("t must be finite");
        return new LonLat(a.lon() + (b.lon() - a.lon()) * t, a.lat() + (b.lat() - a.lat()) * t);
    }
}
