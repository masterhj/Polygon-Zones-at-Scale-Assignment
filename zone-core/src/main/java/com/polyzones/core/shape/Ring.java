package com.polyzones.core.shape;

import com.polyzones.core.geo.BoundingBox;
import com.polyzones.core.geo.GeometryTolerance;
import com.polyzones.core.geo.Location;
import com.polyzones.core.geo.LonLat;
import java.util.List;
import java.util.function.IntConsumer;

/** Immutable, explicitly closed ring. Instances are created only by PolygonShape after validation. */
public final class Ring {
    private final double[] xs, ys;
    private final BoundingBox bounds;
    private final double signedArea;
    private final EdgeBandIndex edgeBands;

    Ring(List<LonLat> points, boolean ccw, double eps) {
        int n = points.size() - 1;
        xs = new double[n + 1]; ys = new double[n + 1];
        for (int i = 0; i < n; i++) { xs[i] = points.get(i).lon(); ys[i] = points.get(i).lat(); }
        xs[n] = xs[0]; ys[n] = ys[0];
        double area = area(xs, ys, n);
        if ((area > 0) != ccw) { reverse(xs, n); reverse(ys, n); area = -area; }
        signedArea = area;
        double minX = xs[0], maxX = xs[0], minY = ys[0], maxY = ys[0];
        for (int i = 1; i < n; i++) { minX = Math.min(minX, xs[i]); maxX = Math.max(maxX, xs[i]); minY = Math.min(minY, ys[i]); maxY = Math.max(maxY, ys[i]); }
        bounds = new BoundingBox(minX, minY, maxX, maxY);
        edgeBands = new EdgeBandIndex(ys, n, eps);
    }

    private static void reverse(double[] values, int n) { for (int i = 0; i < n / 2; i++) { double t = values[i]; values[i] = values[n - 1 - i]; values[n - 1 - i] = t; } values[n] = values[0]; }
    static double area(double[] x, double[] y, int n) {
        double sum = 0, correction = 0;
        for (int i = 0; i < n; i++) {
            double term = (x[i] - x[0]) * (y[i + 1] - y[0]) - (x[i + 1] - x[0]) * (y[i] - y[0]) - correction;
            double next = sum + term; correction = (next - sum) - term; sum = next;
        }
        return sum / 2;
    }
    public int edgeCount() { return xs.length - 1; }
    public int vertexCount() { return edgeCount(); }
    public double signedArea() { return signedArea; }
    public BoundingBox bounds() { return bounds; }
    double x(int i) { return xs[i]; }
    double y(int i) { return ys[i]; }
    public void forEachEdgeCandidate(double minY, double maxY, GeometryTolerance tol, IntConsumer consumer) { edgeBands.forRange(minY, maxY, consumer); }

    public Location locate(double x, double y, GeometryTolerance tol) {
        double eps = tol.boundaryEpsDeg();
        if (!bounds.contains(x, y, eps)) return Location.OUTSIDE;
        boolean[] boundary = {false};
        edgeBands.forPoint(y, edge -> { if (!boundary[0] && distanceToSegment(x, y, xs[edge], ys[edge], xs[edge + 1], ys[edge + 1]) <= eps) boundary[0] = true; });
        if (boundary[0]) return Location.BOUNDARY;
        boolean[] inside = {false};
        edgeBands.forPoint(y, edge -> {
            double x1 = xs[edge], y1 = ys[edge], x2 = xs[edge + 1], y2 = ys[edge + 1];
            if ((y1 > y) != (y2 > y)) {
                double crossingX = x1 + (y - y1) * (x2 - x1) / (y2 - y1);
                if (x < crossingX) inside[0] = !inside[0];
            }
        });
        return inside[0] ? Location.INSIDE : Location.OUTSIDE;
    }
    static double distanceToSegment(double px, double py, double ax, double ay, double bx, double by) {
        double dx = bx - ax, dy = by - ay, len2 = dx * dx + dy * dy;
        double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / len2));
        return Math.hypot(px - (ax + t * dx), py - (ay + t * dy));
    }
}
