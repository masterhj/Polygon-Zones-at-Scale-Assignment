package com.polyzones.core.trip;

import com.polyzones.core.geo.BoundingBox;
import com.polyzones.core.geo.GeometryTolerance;
import com.polyzones.core.geo.Haversine;
import com.polyzones.core.geo.LonLat;
import com.polyzones.core.index.ZoneSnapshot;
import com.polyzones.core.shape.PolygonShape;
import com.polyzones.core.shape.Ring;
import com.polyzones.core.shape.SegmentIntersector;
import com.polyzones.core.zone.Zone;
import com.polyzones.core.zone.ZoneType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;

/** Splits a GPS chord at every relevant polygon boundary and assigns length-proportional distances. */
public final class SegmentSplitter {
    private static final ZoneTypeFilter BILLING = zone -> zone.type() != ZoneType.NO_GO;
    private static final ZoneTypeFilter SAFETY = zone -> zone.type() == ZoneType.NO_GO;
    private SegmentSplitter() { }

    public static List<SubSegment> split(ZoneSnapshot snapshot, LonLat a, LonLat b) {
        return split(snapshot, a, b, GeometryTolerance.DEFAULT, ignored -> true);
    }

    public static List<SubSegment> splitBilling(ZoneSnapshot snapshot, LonLat a, LonLat b, GeometryTolerance tolerance) {
        return split(snapshot, a, b, tolerance, BILLING);
    }

    public static List<SubSegment> splitNoGo(ZoneSnapshot snapshot, LonLat a, LonLat b, GeometryTolerance tolerance) {
        return split(snapshot, a, b, tolerance, SAFETY);
    }

    public static List<SubSegment> split(ZoneSnapshot snapshot, LonLat a, LonLat b,
                                         GeometryTolerance tolerance, ZoneTypeFilter filter) {
        if (snapshot == null || a == null || b == null || tolerance == null || filter == null)
            throw new IllegalArgumentException("Snapshot, segment and filter are required");
        double totalMeters = Haversine.distanceMeters(a, b);
        if (totalMeters == 0 || a.equals(b)) return List.of();
        double eps = tolerance.boundaryEpsDeg();
        BoundingBox search = new BoundingBox(Math.min(a.lon(), b.lon()) - eps, Math.min(a.lat(), b.lat()) - eps,
                Math.max(a.lon(), b.lon()) + eps, Math.max(a.lat(), b.lat()) + eps);
        TreeSet<Double> cuts = new TreeSet<>();
        cuts.add(0d);
        cuts.add(1d);
        snapshot.index().forEachCandidate(search, ordinal -> {
            Zone zone = snapshot.zoneAt(ordinal);
            if (!filter.includes(zone)) return;
            PolygonShape shape = zone.shape();
            collectCrossings(shape.outer(), a, b, tolerance, cuts);
            for (Ring hole : shape.holes()) collectCrossings(hole, a, b, tolerance, cuts);
        });
        List<Double> ordered = deduplicate(cuts, tolerance.tEps());
        List<SubSegment> result = new ArrayList<>(ordered.size() - 1);
        for (int i = 0; i + 1 < ordered.size(); i++) {
            double t0 = ordered.get(i), t1 = ordered.get(i + 1);
            if (t1 - t0 <= tolerance.tEps()) continue;
            LonLat midpoint = Haversine.lerp(a, b, (t0 + t1) / 2);
            TreeSet<Integer> memberships = new TreeSet<>();
            snapshot.index().forEachContaining(midpoint.lon(), midpoint.lat(), ordinal -> {
                Zone zone = snapshot.zoneAt(ordinal);
                if (filter.includes(zone)) memberships.add(ordinal);
            });
            int winner = billingWinner(snapshot, memberships);
            SubSegment next = new SubSegment(t0, t1, winner, memberships, totalMeters * (t1 - t0));
            if (!result.isEmpty()) {
                SubSegment prior = result.getLast();
                if (prior.billingZoneOrdinal() == next.billingZoneOrdinal() && prior.memberships().equals(next.memberships())
                        && Math.abs(prior.t1() - next.t0()) <= tolerance.tEps()) {
                    result.set(result.size() - 1, new SubSegment(prior.t0(), next.t1(), winner,
                            prior.memberships(), prior.meters() + next.meters()));
                    continue;
                }
            }
            result.add(next);
        }
        return List.copyOf(result);
    }

    private static int billingWinner(ZoneSnapshot snapshot, TreeSet<Integer> memberships) {
        return memberships.stream().filter(ordinal -> snapshot.zoneAt(ordinal).type() != ZoneType.NO_GO)
                .min(Comparator.<Integer>comparingInt(ordinal -> snapshot.zoneAt(ordinal).priority()).reversed()
                        .thenComparing(ordinal -> snapshot.zoneAt(ordinal).id()))
                .orElse(-1);
    }

    private static void collectCrossings(Ring ring, LonLat a, LonLat b, GeometryTolerance tolerance, TreeSet<Double> cuts) {
        ring.forEachEdgeCandidate(Math.min(a.lat(), b.lat()) - tolerance.boundaryEpsDeg(),
                Math.max(a.lat(), b.lat()) + tolerance.boundaryEpsDeg(), tolerance, edge -> {
                    LonLat c = ring.vertexAt(edge), d = ring.vertexAt((edge + 1) % ring.edgeCount());
                    SegmentIntersector.Result hit = SegmentIntersector.intersect(a.lon(), a.lat(), b.lon(), b.lat(),
                            c.lon(), c.lat(), d.lon(), d.lat(), tolerance);
                    switch (hit.kind()) {
                        case NONE -> { }
                        case COLLINEAR_OVERLAP -> addOverlapCuts(a, b, c, d, tolerance.tEps(), cuts);
                        case TOUCH_ENDPOINT, PROPER_CROSS -> addCut(hit.t(), tolerance.tEps(), cuts);
                    }
                });
    }

    private static void addOverlapCuts(LonLat a, LonLat b, LonLat c, LonLat d, double tEps, TreeSet<Double> cuts) {
        double dx = b.lon() - a.lon(), dy = b.lat() - a.lat(), len2 = dx * dx + dy * dy;
        if (len2 == 0) return;
        double tc = ((c.lon() - a.lon()) * dx + (c.lat() - a.lat()) * dy) / len2;
        double td = ((d.lon() - a.lon()) * dx + (d.lat() - a.lat()) * dy) / len2;
        double low = Math.max(0, Math.min(tc, td)), high = Math.min(1, Math.max(tc, td));
        if (high >= low - tEps) { addCut(low, tEps, cuts); addCut(high, tEps, cuts); }
    }

    private static void addCut(double t, double tEps, TreeSet<Double> cuts) {
        if (Double.isFinite(t) && t > tEps && t < 1 - tEps) cuts.add(Math.max(0, Math.min(1, t)));
    }

    private static List<Double> deduplicate(TreeSet<Double> cuts, double tEps) {
        List<Double> result = new ArrayList<>(cuts.size());
        for (double t : cuts) {
            if (result.isEmpty() || t - result.getLast() > tEps) result.add(t);
        }
        return result;
    }
}
