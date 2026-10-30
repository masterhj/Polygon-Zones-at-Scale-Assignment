package com.polyzones.core.shape;

import com.polyzones.core.geo.BoundingBox;
import com.polyzones.core.geo.GeometryTolerance;

final class SelfIntersectionDetector {
    private SelfIntersectionDetector() { }
    static boolean hasInvalidIntersection(double[] xs, double[] ys, int n, GeometryTolerance tol) {
        SegmentBoxIndex index = new SegmentBoxIndex(xs, ys, n);
        for (int i = 0; i < n; i++) {
            BoundingBox box = new BoundingBox(Math.min(xs[i], xs[i + 1]), Math.min(ys[i], ys[i + 1]), Math.max(xs[i], xs[i + 1]), Math.max(ys[i], ys[i + 1])).expand(tol.boundaryEpsDeg());
            final int edge = i;
            final boolean[] invalid = {false};
            index.query(box, j -> {
                if (invalid[0] || j <= edge || adjacent(edge, j, n)) return;
                SegmentIntersector.Kind kind = SegmentIntersector.intersect(xs[edge], ys[edge], xs[edge + 1], ys[edge + 1], xs[j], ys[j], xs[j + 1], ys[j + 1], tol).kind();
                if (kind != SegmentIntersector.Kind.NONE) invalid[0] = true;
            });
            if (invalid[0]) return true;
        }
        return false;
    }
    private static boolean adjacent(int a, int b, int n) { return b == a + 1 || (a == 0 && b == n - 1); }
}
