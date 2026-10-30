package com.polyzones.core.geo;

/** Inclusive axis-aligned bounds in longitude/latitude degrees. */
public record BoundingBox(double minLon, double minLat, double maxLon, double maxLat) {
    public BoundingBox {
        if (!Double.isFinite(minLon) || !Double.isFinite(minLat) || !Double.isFinite(maxLon)
                || !Double.isFinite(maxLat) || minLon > maxLon || minLat > maxLat) throw new IllegalArgumentException("Invalid bounding box");
    }
    public boolean contains(double lon, double lat, double eps) { return lon >= minLon - eps && lon <= maxLon + eps && lat >= minLat - eps && lat <= maxLat + eps; }
    public boolean intersects(BoundingBox other) { return minLon <= other.maxLon && maxLon >= other.minLon && minLat <= other.maxLat && maxLat >= other.minLat; }
    public double centerX() { return minLon + (maxLon - minLon) / 2; }
    public double centerY() { return minLat + (maxLat - minLat) / 2; }
    public BoundingBox expand(double eps) {
        if (eps < 0 || !Double.isFinite(eps)) throw new IllegalArgumentException("eps must be finite and non-negative");
        return new BoundingBox(minLon - eps, minLat - eps, maxLon + eps, maxLat + eps);
    }
    public BoundingBox union(BoundingBox other) { return new BoundingBox(Math.min(minLon, other.minLon), Math.min(minLat, other.minLat), Math.max(maxLon, other.maxLon), Math.max(maxLat, other.maxLat)); }
}
