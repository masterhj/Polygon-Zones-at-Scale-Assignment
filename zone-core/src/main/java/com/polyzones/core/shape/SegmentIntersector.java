package com.polyzones.core.shape;

import com.polyzones.core.geo.GeometryTolerance;

/** Segment classification in planar degree coordinates using the D-03 distance tolerance. */
public final class SegmentIntersector {
    public enum Kind { NONE, PROPER_CROSS, TOUCH_ENDPOINT, COLLINEAR_OVERLAP }
    public record Result(Kind kind, double t, double u) { }
    private SegmentIntersector() { }

    public static Result intersect(double ax, double ay, double bx, double by,
                                   double cx, double cy, double dx, double dy, GeometryTolerance tol) {
        double rX = bx - ax, rY = by - ay, sX = dx - cx, sY = dy - cy;
        double rLen = Math.hypot(rX, rY), sLen = Math.hypot(sX, sY), eps = tol.boundaryEpsDeg();
        if (rLen <= eps && sLen <= eps) return near(ax, ay, cx, cy, eps) ? new Result(Kind.TOUCH_ENDPOINT, 0, 0) : none();
        if (rLen <= eps) return pointOn(ax, ay, cx, cy, dx, dy, eps) ? new Result(Kind.TOUCH_ENDPOINT, 0, parameter(ax, ay, cx, cy, dx, dy)) : none();
        if (sLen <= eps) return pointOn(cx, cy, ax, ay, bx, by, eps) ? new Result(Kind.TOUCH_ENDPOINT, parameter(cx, cy, ax, ay, bx, by), 0) : none();
        double qx = cx - ax, qy = cy - ay, cross = cross(rX, rY, sX, sY);
        double threshold = eps * rLen;
        if (Math.abs(cross) <= threshold) {
            if (Math.abs(cross(qx, qy, rX, rY)) > eps * rLen) return none();
            double t0 = (qx * rX + qy * rY) / (rLen * rLen);
            double t1 = ((dx - ax) * rX + (dy - ay) * rY) / (rLen * rLen);
            double lo = Math.max(0, Math.min(t0, t1)), hi = Math.min(1, Math.max(t0, t1));
            if (hi < lo - tol.tEps()) return none();
            return hi - lo <= tol.tEps() ? new Result(Kind.TOUCH_ENDPOINT, (lo + hi) / 2, Double.NaN)
                    : new Result(Kind.COLLINEAR_OVERLAP, lo, Double.NaN);
        }
        double t = cross(qx, qy, sX, sY) / cross, u = cross(qx, qy, rX, rY) / cross;
        double te = tol.tEps();
        if (t < -te || t > 1 + te || u < -te || u > 1 + te) return none();
        boolean endpoint = t <= te || t >= 1 - te || u <= te || u >= 1 - te;
        return new Result(endpoint ? Kind.TOUCH_ENDPOINT : Kind.PROPER_CROSS, t, u);
    }

    private static Result none() { return new Result(Kind.NONE, Double.NaN, Double.NaN); }
    private static double cross(double ax, double ay, double bx, double by) { return ax * by - ay * bx; }
    private static boolean near(double x1, double y1, double x2, double y2, double eps) { return Math.hypot(x1 - x2, y1 - y2) <= eps; }
    private static boolean pointOn(double px, double py, double ax, double ay, double bx, double by, double eps) {
        double dx = bx - ax, dy = by - ay, len2 = dx * dx + dy * dy;
        double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / len2));
        return Math.hypot(px - (ax + t * dx), py - (ay + t * dy)) <= eps;
    }
    private static double parameter(double px, double py, double ax, double ay, double bx, double by) {
        double dx = bx - ax, dy = by - ay, d = dx * dx + dy * dy;
        return d == 0 ? 0 : ((px - ax) * dx + (py - ay) * dy) / d;
    }
}
