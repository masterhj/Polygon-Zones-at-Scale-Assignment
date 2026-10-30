package com.polyzones.core.shape;

import com.polyzones.core.geo.BoundingBox;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.IntConsumer;

/** Packed STR-style envelope index used only to prune exact segment intersection checks. */
final class SegmentBoxIndex {
    private static final int FANOUT = 16;
    private final Node root;
    SegmentBoxIndex(double[] xs, double[] ys, int count) {
        List<Node> level = new ArrayList<>(count);
        for (int i = 0; i < count; i++) level.add(Node.leaf(i, envelope(xs[i], ys[i], xs[i + 1], ys[i + 1])));
        while (level.size() > 1) {
            level.sort(Comparator.comparingDouble(n -> n.bounds.centerX()));
            List<Node> next = new ArrayList<>((level.size() + FANOUT - 1) / FANOUT);
            for (int i = 0; i < level.size(); i += FANOUT) {
                List<Node> group = new ArrayList<>(level.subList(i, Math.min(i + FANOUT, level.size())));
                group.sort(Comparator.comparingDouble(n -> n.bounds.centerY()));
                next.add(Node.branch(group));
            }
            level = next;
        }
        root = level.getFirst();
    }
    void query(BoundingBox bounds, IntConsumer result) { root.query(bounds, result); }
    private static BoundingBox envelope(double ax, double ay, double bx, double by) { return new BoundingBox(Math.min(ax, bx), Math.min(ay, by), Math.max(ax, bx), Math.max(ay, by)); }
    private static final class Node {
        private final BoundingBox bounds; private final List<Node> children; private final int item;
        private Node(BoundingBox bounds, List<Node> children, int item) { this.bounds = bounds; this.children = children; this.item = item; }
        static Node leaf(int item, BoundingBox b) { return new Node(b, List.of(), item); }
        static Node branch(List<Node> kids) {
            BoundingBox b = kids.getFirst().bounds;
            for (int i = 1; i < kids.size(); i++) b = b.union(kids.get(i).bounds);
            return new Node(b, List.copyOf(kids), -1);
        }
        double centerX() { return (bounds.minLon() + bounds.maxLon()) / 2; }
        double centerY() { return (bounds.minLat() + bounds.maxLat()) / 2; }
        void query(BoundingBox q, IntConsumer output) {
            if (!bounds.intersects(q)) return;
            if (item >= 0) output.accept(item); else for (Node child : children) child.query(q, output);
        }
    }
}
