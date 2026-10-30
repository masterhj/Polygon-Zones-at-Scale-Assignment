package com.polyzones.core.shape;

import static org.assertj.core.api.Assertions.assertThat;
import com.polyzones.core.geo.GeometryTolerance;
import com.polyzones.core.geo.LonLat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class EdgeBandIndexTest {
    @Test void rangeQueryMatchesFullEdgeScanAndDeduplicates() {
        List<LonLat> points = new ArrayList<>();
        for (int i = 0; i < 200; i++) { double angle = 2 * Math.PI * i / 200; points.add(new LonLat(Math.cos(angle), Math.sin(angle))); }
        points.add(points.getFirst());
        Ring ring = PolygonShape.of(points, List.of(), new GeometryTolerance(1e-9, 1e-12)).outer();
        Set<Integer> actual = new HashSet<>(); ring.forEachEdgeCandidate(-.8, .8, GeometryTolerance.DEFAULT, actual::add);
        Set<Integer> expected = new HashSet<>();
        for (int i = 0; i < ring.edgeCount(); i++) if (Math.max(ring.y(i), ring.y(i + 1)) + 1e-9 >= -.8 && Math.min(ring.y(i), ring.y(i + 1)) - 1e-9 <= .8) expected.add(i);
        assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
    }
    @Test void pointCandidatesFindLongEdgesAcrossBands() {
        List<LonLat> points = List.of(new LonLat(0, 0), new LonLat(10, 0), new LonLat(10, .01), new LonLat(0, .01), new LonLat(0, 0));
        double[] ys = points.stream().mapToDouble(LonLat::lat).toArray();
        EdgeBandIndex index = new EdgeBandIndex(ys, ys.length - 1, GeometryTolerance.DEFAULT.boundaryEpsDeg());
        Set<Integer> candidates = new HashSet<>(); index.forPoint(.005, candidates::add);
        assertThat(candidates).contains(1, 3);
    }
}
