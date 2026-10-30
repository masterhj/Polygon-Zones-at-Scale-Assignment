package com.polyzones.core.shape;

import static org.assertj.core.api.Assertions.assertThat;
import com.polyzones.core.geo.GeometryTolerance;
import com.polyzones.core.geo.Location;
import com.polyzones.core.geo.LonLat;
import java.util.List;
import org.junit.jupiter.api.Test;

class PolygonShapeBoundaryRuleTest {
    private static final GeometryTolerance TOL = GeometryTolerance.DEFAULT;
    private static List<LonLat> square(double lo, double la, double hi, double ha) { return List.of(new LonLat(lo, la), new LonLat(hi, la), new LonLat(hi, ha), new LonLat(lo, ha), new LonLat(lo, la)); }
    @Test void d02EveryOuterEdgeMidpointAndVertexIsIncluded() {
        PolygonShape shape = PolygonShape.of(square(0, 0, 4, 4), List.of(), TOL);
        for (double[] p : new double[][]{{2,0},{4,2},{2,4},{0,2},{0,0},{4,0},{4,4},{0,4}}) {
            assertThat(shape.locate(p[0], p[1], TOL)).as("D-02 outer boundary at %s,%s", p[0], p[1]).isEqualTo(Location.BOUNDARY);
            assertThat(shape.contains(p[0], p[1], TOL)).as("D-02 outer boundary included").isTrue();
        }
    }
    @Test void d02HoleInteriorIsExcludedButItsEdgeAndVertexAreIncluded() {
        PolygonShape shape = PolygonShape.of(square(0, 0, .01, .01), List.of(square(.003, .003, .007, .007)), TOL);
        assertThat(shape.locate(.005, .005, TOL)).isEqualTo(Location.OUTSIDE);
        assertThat(shape.locate(.003, .005, TOL)).isEqualTo(Location.BOUNDARY);
        assertThat(shape.contains(.003, .005, TOL)).isTrue();
        assertThat(shape.locate(.003, .003, TOL)).isEqualTo(Location.BOUNDARY);
    }
    @Test void epsilonBoundaryDistanceUsesCoordinateDegrees() {
        PolygonShape shape = PolygonShape.of(square(0, 0, 1, 1), List.of(), TOL);
        assertThat(shape.locate(.5, .5 * TOL.boundaryEpsDeg(), TOL)).isEqualTo(Location.BOUNDARY);
        assertThat(shape.locate(.5, 2 * TOL.boundaryEpsDeg(), TOL)).isEqualTo(Location.INSIDE);
    }
}
