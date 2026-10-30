package com.polyzones.core.shape;

import com.polyzones.core.geo.GeometryTolerance;
import com.polyzones.core.geo.Haversine;
import com.polyzones.core.geo.Location;
import com.polyzones.core.geo.LonLat;
import java.util.ArrayList;
import java.util.List;

/** Aggregates structural and topological polygon validation issues. */
public final class PolygonValidator {
    private PolygonValidator() { }

    public static ValidationResult validate(List<LonLat> outer, List<List<LonLat>> holes, GeometryTolerance tol) {
        List<ValidationIssue> issues = new ArrayList<>();
        if (outer == null) outer = List.of();
        if (holes == null) holes = List.of();
        if (holes.size() > GeometryLimits.MAX_HOLES) add(issues, ValidationCode.POLYGON_TOO_LARGE, -1, "hole limit exceeded");
        List<List<LonLat>> rings = new ArrayList<>(); rings.add(outer); rings.addAll(holes);
        int vertices = rings.stream().filter(java.util.Objects::nonNull).mapToInt(List::size).sum() - rings.size();
        if (vertices > GeometryLimits.MAX_VERTICES) add(issues, ValidationCode.POLYGON_TOO_LARGE, -1, "vertex limit exceeded");
        boolean[] usable = new boolean[rings.size()];
        for (int r = 0; r < rings.size(); r++) {
            List<LonLat> points = rings.get(r); boolean ok = true;
            if (points == null) { add(issues, ValidationCode.TOO_FEW_POINTS, r, "ring is absent"); continue; }
            if (points.size() < 4) { add(issues, ValidationCode.TOO_FEW_POINTS, r, "closed ring requires at least four positions"); ok = false; }
            boolean coordinateError = false;
            for (LonLat p : points) {
                if (p == null || p.lon() < -180 || p.lon() > 180 || p.lat() < -90 || p.lat() > 90) { coordinateError = true; break; }
            }
            if (coordinateError) { add(issues, ValidationCode.INVALID_COORDINATE, r, "coordinate outside finite longitude/latitude range"); ok = false; }
            if (points.size() >= 2 && !points.getFirst().equals(points.getLast())) { add(issues, ValidationCode.POLYGON_NOT_CLOSED, r, "first and last positions must match exactly"); ok = false; }
            for (int i = 1; i < points.size(); i++) if (points.get(i - 1) != null && points.get(i) != null && points.get(i - 1).equals(points.get(i))) {
                add(issues, ValidationCode.DUPLICATE_CONSECUTIVE_POINTS, r, "consecutive positions must differ"); ok = false; break;
            }
            if (points.stream().anyMatch(p -> p != null && (Math.abs(p.lon()) == 180 || Math.abs(p.lat()) > GeometryLimits.MAX_ABS_LATITUDE))) {
                add(issues, ValidationCode.ANTIMERIDIAN_UNSUPPORTED, r, "ring touches antimeridian or unsupported polar latitude"); ok = false;
            }
            if (points.stream().noneMatch(java.util.Objects::isNull) && longitudeSpan(points) > 180) {
                add(issues, ValidationCode.ANTIMERIDIAN_UNSUPPORTED, r, "ring longitude span exceeds 180 degrees"); ok = false;
            }
            if (points.size() >= 4 && points.stream().noneMatch(java.util.Objects::isNull)) {
                double area = area(points);
                boolean zeroArea = area == 0;
                if (zeroArea) { add(issues, ValidationCode.ZERO_AREA, r, "ring has zero signed area"); }
                if (ok && SelfIntersectionDetector.hasInvalidIntersection(xs(points), ys(points), points.size() - 1, tol)) {
                    add(issues, ValidationCode.POLYGON_SELF_INTERSECTS, r, "ring edges intersect or touch"); ok = false;
                }
            }
            usable[r] = ok;
        }
        if (rings.size() > GeometryLimits.MAX_HOLES + 1) return new ValidationResult(issues);
        if (issues.stream().anyMatch(i -> i.code() == ValidationCode.POLYGON_TOO_LARGE)) return new ValidationResult(issues);
        if (usable.length > 0 && usable[0]) {
            List<LonLat> outerPts = rings.getFirst();
            for (int h = 1; h < rings.size(); h++) if (usable[h]) {
                if (ringsIntersect(outerPts, rings.get(h), tol)) add(issues, ValidationCode.RINGS_TOUCH_OR_CROSS, h, "hole touches or crosses outer ring");
                else if (locate(outerPts, rings.get(h).getFirst(), tol) != Location.INSIDE) add(issues, ValidationCode.HOLE_OUTSIDE_OUTER, h, "hole is not strictly inside outer ring");
                for (int other = 1; other < h; other++) if (usable[other]) {
                    if (ringsIntersect(rings.get(other), rings.get(h), tol)) add(issues, ValidationCode.RINGS_TOUCH_OR_CROSS, h, "holes touch or cross");
                    else if (locate(rings.get(other), rings.get(h).getFirst(), tol) != Location.OUTSIDE
                            || locate(rings.get(h), rings.get(other).getFirst(), tol) != Location.OUTSIDE) add(issues, ValidationCode.HOLES_OVERLAP, h, "holes overlap or nest");
                }
            }
        }
        if (usable.length > 0 && usable[0] && rings.getFirst().stream().noneMatch(java.util.Objects::isNull)
                && geographicDiameter(rings.getFirst()) > GeometryLimits.MAX_REGION_METERS) add(issues, ValidationCode.POLYGON_TOO_LARGE, 0, "geographic bbox exceeds regional extent limit");
        return new ValidationResult(issues);
    }

    private static void add(List<ValidationIssue> list, ValidationCode code, int ring, String detail) { list.add(new ValidationIssue(code, ring, detail)); }
    private static double area(List<LonLat> p) { double[] x = xs(p), y = ys(p); return Ring.area(x, y, p.size() - 1); }
    private static double[] xs(List<LonLat> p) { double[] x = new double[p.size()]; for (int i = 0; i < p.size(); i++) x[i] = p.get(i).lon(); return x; }
    private static double[] ys(List<LonLat> p) { double[] y = new double[p.size()]; for (int i = 0; i < p.size(); i++) y[i] = p.get(i).lat(); return y; }
    private static boolean ringsIntersect(List<LonLat> a, List<LonLat> b, GeometryTolerance tol) {
        double[] ax = xs(a), ay = ys(a), bx = xs(b), by = ys(b); SegmentBoxIndex index = new SegmentBoxIndex(bx, by, b.size() - 1);
        for (int i = 0; i < a.size() - 1; i++) {
            var box = new com.polyzones.core.geo.BoundingBox(Math.min(ax[i], ax[i + 1]), Math.min(ay[i], ay[i + 1]), Math.max(ax[i], ax[i + 1]), Math.max(ay[i], ay[i + 1])).expand(tol.boundaryEpsDeg());
            final int edge = i;
            final boolean[] hit = {false}; index.query(box, j -> { if (SegmentIntersector.intersect(ax[edge], ay[edge], ax[edge + 1], ay[edge + 1], bx[j], by[j], bx[j + 1], by[j + 1], tol).kind() != SegmentIntersector.Kind.NONE) hit[0] = true; });
            if (hit[0]) return true;
        }
        return false;
    }
    private static Location locate(List<LonLat> ring, LonLat point, GeometryTolerance tol) {
        double[] x = xs(ring), y = ys(ring); boolean inside = false;
        for (int i = 0; i < ring.size() - 1; i++) {
            if (Ring.distanceToSegment(point.lon(), point.lat(), x[i], y[i], x[i + 1], y[i + 1]) <= tol.boundaryEpsDeg()) return Location.BOUNDARY;
            if ((y[i] > point.lat()) != (y[i + 1] > point.lat()) && point.lon() < x[i] + (point.lat() - y[i]) * (x[i + 1] - x[i]) / (y[i + 1] - y[i])) inside = !inside;
        }
        return inside ? Location.INSIDE : Location.OUTSIDE;
    }
    private static double geographicDiameter(List<LonLat> points) {
        double minLon = 180, maxLon = -180, minLat = 90, maxLat = -90;
        for (LonLat p : points) { minLon = Math.min(minLon, p.lon()); maxLon = Math.max(maxLon, p.lon()); minLat = Math.min(minLat, p.lat()); maxLat = Math.max(maxLat, p.lat()); }
        return Math.max(Haversine.distanceMeters(new LonLat(minLon, minLat), new LonLat(maxLon, maxLat)), Haversine.distanceMeters(new LonLat(minLon, maxLat), new LonLat(maxLon, minLat)));
    }
    private static double longitudeSpan(List<LonLat> points) {
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (LonLat p : points) { min = Math.min(min, p.lon()); max = Math.max(max, p.lon()); }
        return max - min;
    }
}
