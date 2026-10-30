package com.polyzones.core.shape;

import static org.assertj.core.api.Assertions.assertThat;
import com.polyzones.core.geo.GeometryTolerance;
import java.util.Random;
import org.junit.jupiter.api.Test;

class SelfIntersectionDetectorTest {
    @Test void strDetectorMatchesQuadraticOracleOnTenThousandSeededPolylines() {
        Random random = new Random(0x51f1L);
        for (int sample = 0; sample < 10_000; sample++) {
            int n = 8 + random.nextInt(9);
            double[] x = new double[n + 1], y = new double[n + 1];
            for (int i = 0; i < n; i++) { x[i] = random.nextDouble() * 2 - 1; y[i] = random.nextDouble() * 2 - 1; }
            x[n] = x[0]; y[n] = y[0];
            assertThat(SelfIntersectionDetector.hasInvalidIntersection(x, y, n, GeometryTolerance.DEFAULT))
                    .as("polyline sample %s", sample).isEqualTo(naive(x, y, n));
        }
    }
    private static boolean naive(double[] x, double[] y, int n) {
        for (int i=0;i<n;i++) for (int j=i+1;j<n;j++) {
            if (j == i+1 || (i == 0 && j == n-1)) continue;
            if (SegmentIntersector.intersect(x[i],y[i],x[i+1],y[i+1],x[j],y[j],x[j+1],y[j+1],GeometryTolerance.DEFAULT).kind() != SegmentIntersector.Kind.NONE) return true;
        }
        return false;
    }
}
