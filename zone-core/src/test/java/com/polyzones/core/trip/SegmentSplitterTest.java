package com.polyzones.core.trip;

import static org.assertj.core.api.Assertions.assertThat;

import com.polyzones.core.geo.GeometryTolerance;
import com.polyzones.core.geo.Haversine;
import com.polyzones.core.geo.LonLat;
import com.polyzones.core.index.SnapshotBuilder;
import com.polyzones.core.index.ZoneSnapshot;
import com.polyzones.core.shape.PolygonShape;
import com.polyzones.core.zone.Zone;
import com.polyzones.core.zone.ZoneKey;
import com.polyzones.core.zone.ZoneType;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SegmentSplitterTest {
    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final ZoneKey A = key(1), B = key(2), N = key(3);

    @Test
    @DisplayName("R3.1 / D-04: segment crossing a square is split at exact boundary parameters")
    void crossesConvexPolygonTwice() {
        ZoneSnapshot snapshot = snapshot(box(A, 0, 0, .1, .1, ZoneType.CITY, 0));
        LonLat a = new LonLat(-.1, .05), b = new LonLat(.2, .05);
        List<SubSegment> result = SegmentSplitter.split(snapshot, a, b);
        assertThat(result).hasSize(3);
        assertThat(result.get(0).t0()).isZero(); assertThat(result.get(0).t1()).isCloseTo(1d / 3, within(1e-12));
        assertThat(result.get(1).t0()).isCloseTo(1d / 3, within(1e-12)); assertThat(result.get(1).t1()).isCloseTo(2d / 3, within(1e-12));
        assertThat(result.get(1).billingZoneOrdinal()).isEqualTo(snapshot.ordinalById().get(A));
        assertThat(result.get(2).t1()).isEqualTo(1);
        assertThat(result.stream().mapToDouble(SubSegment::meters).sum()).isCloseTo(Haversine.distanceMeters(a, b), within(1e-8));
    }

    @Test
    @DisplayName("R3.1: concave notches produce multiple disjoint inside intervals")
    void crossesConcaveNotch() {
        List<LonLat> lShape = points(0, 0, .1, 0, .1, .1, .06, .1, .06, .04, .04, .04, .04, .1, 0, .1, 0, 0);
        ZoneSnapshot snapshot = snapshot(zone(A, 1, ZoneType.CITY, 0, lShape, List.of()));
        List<SubSegment> result = SegmentSplitter.split(snapshot, new LonLat(-.1, .07), new LonLat(.2, .07));
        assertThat(result.stream().filter(s -> s.billingZoneOrdinal() >= 0)).hasSize(2);
        List<Double> starts = result.stream().filter(s -> s.billingZoneOrdinal() >= 0).map(SubSegment::t0).toList();
        assertThat(starts).hasSize(2);
        assertThat(starts.get(0)).isCloseTo(1d / 3, within(1e-12));
        assertThat(starts.get(1)).isCloseTo(.16 / .3, within(1e-12));
    }

    @Test
    @DisplayName("R3.1: crossing a hole produces outside membership in its interior")
    void crossesHole() {
        List<LonLat> outer = points(0, 0, .1, 0, .1, .1, 0, .1, 0, 0);
        List<LonLat> hole = points(.04, .04, .06, .04, .06, .06, .04, .06, .04, .04);
        ZoneSnapshot snapshot = snapshot(zone(A, 1, ZoneType.CITY, 0, outer, List.of(hole)));
        List<SubSegment> result = SegmentSplitter.split(snapshot, new LonLat(-.1, .05), new LonLat(.2, .05));
        assertThat(result).hasSize(5);
        assertThat(result.get(2).memberships()).isEmpty();
        assertThat(result.get(1).memberships()).contains(snapshot.ordinalById().get(A));
        assertThat(result.get(3).memberships()).contains(snapshot.ordinalById().get(A));
    }

    @Test
    @DisplayName("R3.1 / D-02: a segment along a closed polygon edge is inside")
    void boundaryCollinearOverlapIsInside() {
        ZoneSnapshot snapshot = snapshot(box(A, 0, 0, .1, .1, ZoneType.CITY, 0));
        List<SubSegment> result = SegmentSplitter.split(snapshot, new LonLat(-.05, 0), new LonLat(.15, 0));
        assertThat(result).hasSize(3);
        assertThat(result.get(1).memberships()).contains(snapshot.ordinalById().get(A));
        assertThat(result.get(1).t0()).isCloseTo(.25, within(1e-12));
        assertThat(result.get(1).t1()).isCloseTo(.75, within(1e-12));
    }

    @Test
    @DisplayName("R3.1: tangential vertex contact creates no positive-length inside interval")
    void tangentVertexDoesNotCreateInsideLength() {
        ZoneSnapshot snapshot = snapshot(box(A, 0, 0, .1, .1, ZoneType.CITY, 0));
        List<SubSegment> result = SegmentSplitter.split(snapshot, new LonLat(.1, .1), new LonLat(.2, 0));
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().memberships()).isEmpty();
    }

    @Test
    @DisplayName("D-06: highest-priority billable zone wins overlaps; no-go zones never win billing")
    void appliesPriorityAndNoGoFilter() {
        ZoneSnapshot snapshot = snapshot(box(A, 0, 0, .1, .1, ZoneType.CITY, 0),
                box(B, .04, 0, .08, .1, ZoneType.AIRPORT, 10), box(N, .045, 0, .055, .1, ZoneType.NO_GO, 100));
        LonLat p1 = new LonLat(.02, .05), p2 = new LonLat(.05, .05);
        List<SubSegment> all = SegmentSplitter.split(snapshot, p1, new LonLat(.09, .05));
        assertThat(snapshot.zoneAt(all.getFirst().billingZoneOrdinal()).id()).isEqualTo(A);
        SubSegment overlap = all.stream().filter(s -> s.memberships().contains(snapshot.ordinalById().get(B))).findFirst().orElseThrow();
        assertThat(snapshot.zoneAt(overlap.billingZoneOrdinal()).id()).isEqualTo(B);
        List<SubSegment> billing = SegmentSplitter.splitBilling(snapshot, p1, p2, GeometryTolerance.DEFAULT);
        assertThat(billing.stream().flatMap(s -> s.memberships().stream()).map(snapshot::zoneAt).map(Zone::type))
                .doesNotContain(ZoneType.NO_GO);
        List<SubSegment> safety = SegmentSplitter.splitNoGo(snapshot, p1, p2, GeometryTolerance.DEFAULT);
        assertThat(safety).allSatisfy(s -> assertThat(s.memberships()).allSatisfy(o -> assertThat(snapshot.zoneAt(o).type()).isEqualTo(ZoneType.NO_GO)));
    }

    @Test
    @DisplayName("R3.1: zero-length segment emits no distance intervals")
    void zeroLength() {
        assertThat(SegmentSplitter.split(snapshot(box(A, 0, 0, .1, .1, ZoneType.CITY, 0)),
                new LonLat(.05, .05), new LonLat(.05, .05))).isEmpty();
    }

    @Test
    @DisplayName("R3.1: entirely-inside and entirely-outside chords retain a single exact interval")
    void insideAndOutsideSegments() {
        ZoneSnapshot snapshot = snapshot(box(A, 0, 0, .1, .1, ZoneType.CITY, 0));
        List<SubSegment> inside = SegmentSplitter.split(snapshot, new LonLat(.02, .05), new LonLat(.08, .05));
        assertThat(inside).hasSize(1);
        assertThat(inside.getFirst().memberships()).contains(snapshot.ordinalById().get(A));
        assertThat(inside.getFirst().t0()).isZero();
        assertThat(inside.getFirst().t1()).isEqualTo(1);
        List<SubSegment> outside = SegmentSplitter.split(snapshot, new LonLat(-.2, -.2), new LonLat(-.1, -.1));
        assertThat(outside).singleElement().satisfies(segment -> {
            assertThat(segment.memberships()).isEmpty();
            assertThat(segment.billingZoneOrdinal()).isEqualTo(-1);
        });
    }

    @Test
    @DisplayName("R3.1: vertex crossings deduplicate coincident ring-edge parameters")
    void passesThroughPolygonVertices() {
        ZoneSnapshot snapshot = snapshot(box(A, 0, 0, .1, .1, ZoneType.CITY, 0));
        List<SubSegment> segments = SegmentSplitter.split(snapshot, new LonLat(-.1, -.1), new LonLat(.2, .2));
        assertThat(segments).hasSize(3);
        assertThat(segments.get(1).billingZoneOrdinal()).isEqualTo(snapshot.ordinalById().get(A));
        assertThat(segments.get(1).t0()).isCloseTo(1d / 3, within(1e-12));
        assertThat(segments.get(1).t1()).isCloseTo(2d / 3, within(1e-12));
    }

    @Test
    @DisplayName("R3.1: sub-millimetre segments still preserve their length ledger")
    void subMillimetreSegmentIsNotDiscarded() {
        ZoneSnapshot snapshot = snapshot(box(A, 0, 0, .00000001, .00000001, ZoneType.CITY, 0));
        LonLat from = new LonLat(.000000001, .000000005), to = new LonLat(.000000009, .000000005);
        List<SubSegment> segments = SegmentSplitter.split(snapshot, from, to);
        assertThat(segments).singleElement().satisfies(segment -> {
            assertThat(segment.memberships()).contains(snapshot.ordinalById().get(A));
            assertThat(segment.meters()).isCloseTo(Haversine.distanceMeters(from, to), within(1e-12));
        });
    }

    private static ZoneSnapshot snapshot(Zone... zones) { return SnapshotBuilder.build(TENANT, 1, List.of(zones)); }
    private static Zone box(ZoneKey id, double minX, double minY, double maxX, double maxY, ZoneType type, int priority) {
        return zone(id, 1, type, priority, points(minX, minY, maxX, minY, maxX, maxY, minX, maxY, minX, minY), List.of());
    }
    private static Zone zone(ZoneKey id, int version, ZoneType type, int priority, List<LonLat> outer, List<List<LonLat>> holes) {
        return new Zone(id, version, type, priority, PolygonShape.of(outer, holes, GeometryTolerance.DEFAULT));
    }
    private static List<LonLat> points(double... xy) {
        List<LonLat> result = new ArrayList<>();
        for (int i = 0; i < xy.length; i += 2) result.add(new LonLat(xy[i], xy[i + 1]));
        return List.copyOf(result);
    }
    private static ZoneKey key(long id) { return new ZoneKey(new UUID(0, id)); }
    private static org.assertj.core.data.Offset<Double> within(double value) { return org.assertj.core.data.Offset.offset(value); }
}
