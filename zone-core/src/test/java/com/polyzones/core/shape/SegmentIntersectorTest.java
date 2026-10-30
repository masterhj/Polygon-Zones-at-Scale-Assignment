package com.polyzones.core.shape;

import static org.assertj.core.api.Assertions.assertThat;
import com.polyzones.core.geo.GeometryTolerance;
import org.junit.jupiter.api.Test;

class SegmentIntersectorTest {
    private final GeometryTolerance tol = GeometryTolerance.DEFAULT;
    @Test void distinguishesCrossTouchOverlapAndDisjoint() {
        assertThat(hit(0,0,2,2,0,2,2,0).kind()).isEqualTo(SegmentIntersector.Kind.PROPER_CROSS);
        assertThat(hit(0,0,1,0,1,0,1,1).kind()).isEqualTo(SegmentIntersector.Kind.TOUCH_ENDPOINT);
        assertThat(hit(0,0,3,0,1,0,4,0).kind()).isEqualTo(SegmentIntersector.Kind.COLLINEAR_OVERLAP);
        assertThat(hit(0,0,1,0,2,0,3,0).kind()).isEqualTo(SegmentIntersector.Kind.NONE);
    }
    @Test void crossingReturnsBothParameters() {
        var result = hit(0,0,2,2,0,2,2,0);
        assertThat(result.t()).isEqualTo(.5); assertThat(result.u()).isEqualTo(.5);
    }
    private SegmentIntersector.Result hit(double ax,double ay,double bx,double by,double cx,double cy,double dx,double dy) { return SegmentIntersector.intersect(ax,ay,bx,by,cx,cy,dx,dy,tol); }
}
