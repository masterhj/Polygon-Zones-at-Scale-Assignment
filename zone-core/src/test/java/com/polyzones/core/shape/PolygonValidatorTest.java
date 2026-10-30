package com.polyzones.core.shape;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.polyzones.core.geo.GeometryTolerance;
import com.polyzones.core.geo.LonLat;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class PolygonValidatorTest {
    private static final GeometryTolerance TOL = GeometryTolerance.DEFAULT;
    private static List<LonLat> ring(double... coords) { List<LonLat> p = new ArrayList<>(); for (int i=0;i<coords.length;i+=2) p.add(new LonLat(coords[i],coords[i+1])); return p; }
    private static List<LonLat> square(double a,double b,double c,double d) { return ring(a,b,c,b,c,d,a,d,a,b); }
    private static List<LonLat> clockwiseSquare(double a,double b,double c,double d) { return ring(a,b,a,d,c,d,c,b,a,b); }
    @Test void reportsClosureDuplicateAndCoordinateIssuesTogether() {
        var result = PolygonValidator.validate(ring(0,0, 2,0, 2,0, 0,2), List.of(), TOL);
        assertThat(result.issues().stream().map(ValidationIssue::code)).contains(ValidationCode.POLYGON_NOT_CLOSED, ValidationCode.DUPLICATE_CONSECUTIVE_POINTS);
        assertThat(PolygonValidator.validate(ring(0,0, 2,0, 2,2, 0,0), List.of(), TOL).valid()).isTrue();
        var badCoordinate = PolygonValidator.validate(ring(0,0, 200,0, 2,2, 0,0), List.of(), TOL);
        assertThat(badCoordinate.issues()).extracting(ValidationIssue::code).contains(ValidationCode.INVALID_COORDINATE);
    }
    @Test void rejectsSelfCrossingAndZeroAreaButAcceptsSimpleConcavity() {
        assertCode(ring(0,0, 2,2, 0,2, 2,0, 0,0), ValidationCode.POLYGON_SELF_INTERSECTS);
        assertCode(ring(0,0, 1,0, 2,0, 0,0), ValidationCode.ZERO_AREA);
        assertThat(PolygonShape.of(ring(0,0, 4,0, 4,4, 3,4, 3,1, 1,1, 1,4, 0,4, 0,0), List.of(), TOL).vertexCount()).isEqualTo(8);
    }
    @Test void rejectsOutsideTouchingAndNestedHoles() {
        assertCode(square(0,0,10,10), List.of(square(11,11,12,12)), ValidationCode.HOLE_OUTSIDE_OUTER);
        assertCode(square(0,0,10,10), List.of(square(0,2,2,4)), ValidationCode.RINGS_TOUCH_OR_CROSS);
        assertCode(square(0,0,.01,.01), List.of(square(.002,.002,.008,.008), square(.003,.003,.004,.004)), ValidationCode.HOLES_OVERLAP);
        var accepted = PolygonValidator.validate(square(0,0,2,2), List.of(square(.4,.4,.8,.8), square(1.1,1.1,1.5,1.5)), TOL);
        assertThat(accepted.issues()).as("three disjoint inner holes").isEmpty();
    }
    @Test void normalizesOuterOrientationWithoutChangingContainment() {
        PolygonShape shape = PolygonShape.of(clockwiseSquare(0,0,2,2), List.of(), TOL);
        assertThat(shape.outer().signedArea()).isPositive();
        assertThat(shape.contains(1,1,TOL)).isTrue();
    }
    @Test void rejectsUnsupportedExtentAntimeridianAndOversizedHoleCount() {
        assertCode(square(0,0,20,20), ValidationCode.POLYGON_TOO_LARGE);
        assertCode(ring(179,0, 180,0, 180,1, 179,1, 179,0), ValidationCode.ANTIMERIDIAN_UNSUPPORTED);
        assertCode(ring(-100,0, 100,0, 100,1, -100,1, -100,0), ValidationCode.ANTIMERIDIAN_UNSUPPORTED);
        List<List<LonLat>> tooMany = new ArrayList<>();
        for (int i=0;i<257;i++) tooMany.add(square(.1+i*.0001,.1,.10005+i*.0001,.10005));
        assertThat(PolygonValidator.validate(square(0,0,1,1), tooMany, TOL).issues()).extracting(ValidationIssue::code).contains(ValidationCode.POLYGON_TOO_LARGE);
    }
    @Test @Timeout(10) void acceptsLargeSimpleRingWithinConfiguredVertexLimit() {
        int n=49_999; List<LonLat> p = new ArrayList<>(n+1);
        for (int i=0;i<n;i++) { double a=2*Math.PI*i/n; double radius=1+(i%2)*.00001; p.add(new LonLat(radius*Math.cos(a),radius*Math.sin(a))); }
        p.add(p.getFirst());
        assertThat(PolygonShape.of(p,List.of(),TOL).vertexCount()).isEqualTo(n);
    }
    private static void assertCode(List<LonLat> outer, ValidationCode code) { assertCode(outer,List.of(),code); }
    private static void assertCode(List<LonLat> outer,List<List<LonLat>> holes,ValidationCode code) {
        assertThatThrownBy(() -> PolygonShape.of(outer,holes,TOL)).isInstanceOf(InvalidPolygonException.class)
                .extracting(e -> ((InvalidPolygonException)e).result().issues().stream().map(ValidationIssue::code).toList()).asList().contains(code);
    }
}
