package com.polyzones.core.shape;

import static org.assertj.core.api.Assertions.assertThat;
import com.polyzones.core.geo.GeometryTolerance;
import com.polyzones.core.geo.LonLat;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.algorithm.locate.IndexedPointInAreaLocator;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;

class GeometryPropertyTest {
    private static final GeometryTolerance TOL = GeometryTolerance.DEFAULT;
    @Test void seededPointsAgreeWithJtsAwayFromEdges() {
        Random random = new Random(0x5eedc0deL);
        GeometryFactory factory = new GeometryFactory();
        int compared = 0;
        for (int polygonNumber = 0; polygonNumber < 100 && compared < 100_000; polygonNumber++) {
            int vertices = 8 + random.nextInt(20);
            List<LonLat> ring = star(random, vertices, .005, .002);
            PolygonShape shape = PolygonShape.of(ring, List.of(), TOL);
            LinearRing shell = factory.createLinearRing(ring.stream().map(p -> new Coordinate(p.lon(), p.lat())).toArray(Coordinate[]::new));
            Polygon jts = factory.createPolygon(shell);
            IndexedPointInAreaLocator locator = new IndexedPointInAreaLocator(jts);
            for (int pointNumber = 0; pointNumber < 1_100 && compared < 100_000; pointNumber++) {
                double x = (random.nextDouble() * 2 - 1) * .009, y = (random.nextDouble() * 2 - 1) * .009;
                if (jts.getBoundary().distance(factory.createPoint(new Coordinate(x, y))) < 1e-7) continue;
                boolean expected = locator.locate(new Coordinate(x, y)) != org.locationtech.jts.geom.Location.EXTERIOR;
                assertThat(shape.contains(x, y, TOL)).as("seeded polygon %s point %s", polygonNumber, pointNumber).isEqualTo(expected);
                compared++;
            }
        }
        assertThat(compared).isEqualTo(100_000);
    }
    private static List<LonLat> star(Random random, int count, double base, double variation) {
        List<LonLat> result = new ArrayList<>(count + 1);
        for (int i = 0; i < count; i++) {
            double angle = 2 * Math.PI * i / count;
            double radius = base + random.nextDouble() * variation;
            result.add(new LonLat(radius * Math.cos(angle), radius * Math.sin(angle)));
        }
        result.add(result.getFirst());
        return result;
    }
}
