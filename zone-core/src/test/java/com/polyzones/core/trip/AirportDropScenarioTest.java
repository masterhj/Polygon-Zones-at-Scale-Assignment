package com.polyzones.core.trip;

import static org.assertj.core.api.Assertions.assertThat;

import com.polyzones.core.geo.GeometryTolerance;
import com.polyzones.core.geo.LonLat;
import com.polyzones.core.index.SnapshotBuilder;
import com.polyzones.core.index.ZoneSnapshot;
import com.polyzones.core.shape.PolygonShape;
import com.polyzones.core.zone.Zone;
import com.polyzones.core.zone.ZoneKey;
import com.polyzones.core.zone.ZoneType;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AirportDropScenarioTest {
    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TRIP = UUID.fromString("10000000-0000-0000-0000-000000000042");
    private static final ZoneKey CITY = new ZoneKey(UUID.fromString("20000000-0000-0000-0000-000000000001"));
    private static final ZoneKey AIRPORT = new ZoneKey(UUID.fromString("20000000-0000-0000-0000-000000000002"));

    @Test
    @DisplayName("EX1 / R3.1: 42 km airport route attributes 30.4 km city and 11.6 km airport for both ping spacings")
    void airportDropWithDenseAndSingleSegment() {
        ZoneSnapshot snapshot = routeSnapshot();
        TripSummary dense = run(snapshot, 84);
        TripSummary single = run(snapshot, 1);
        assertTrip(dense);
        assertTrip(single);
    }

    private static TripSummary run(ZoneSnapshot snapshot, int segments) {
        TripAttributor attributor = new TripAttributor(TENANT, TRIP, snapshot);
        double lonEnd = longitudeForMeters(42_000);
        Instant start = Instant.parse("2026-10-31T20:00:00Z");
        for (int i = 0; i <= segments; i++) {
            double lon = lonEnd * i / segments;
            Ping ping = new Ping(TRIP, i, start.plusSeconds(i * 30L), new LonLat(lon, 0), null);
            attributor.apply(ping, snapshot, new NightWindow(LocalTime.of(22, 0), LocalTime.of(6, 0)), ZoneId.of("UTC"));
        }
        return attributor.complete(start.plusSeconds((segments + 1L) * 30)).summary();
    }

    private static void assertTrip(TripSummary summary) {
        assertThat(summary.metersByZoneKey().get(CITY.value().toString())).isCloseTo(30_400, org.assertj.core.data.Offset.offset(5d));
        assertThat(summary.metersByZoneKey().get(AIRPORT.value().toString())).isCloseTo(11_600, org.assertj.core.data.Offset.offset(5d));
        assertThat(summary.totalMeters()).isCloseTo(42_000, org.assertj.core.data.Offset.offset(5d));
        assertThat(summary.roundedKilometersByZoneKey().get(CITY.value().toString())).isEqualByComparingTo("30.400");
        assertThat(summary.roundedKilometersByZoneKey().get(AIRPORT.value().toString())).isEqualByComparingTo("11.600");
        assertThat(summary.roundedTotalKilometers()).isEqualByComparingTo("42.000");
    }

    private static ZoneSnapshot routeSnapshot() {
        double boundary = longitudeForMeters(30_400), end = longitudeForMeters(42_000);
        Zone city = rectangle(CITY, -0.05, boundary, ZoneType.CITY, 0);
        Zone airport = rectangle(AIRPORT, boundary, end + .05, ZoneType.AIRPORT, 10);
        return SnapshotBuilder.build(TENANT, 1, List.of(city, airport));
    }

    private static Zone rectangle(ZoneKey id, double minLon, double maxLon, ZoneType type, int priority) {
        List<LonLat> ring = List.of(new LonLat(minLon, -.05), new LonLat(maxLon, -.05), new LonLat(maxLon, .05),
                new LonLat(minLon, .05), new LonLat(minLon, -.05));
        return new Zone(id, 1, type, priority, PolygonShape.of(ring, List.of(), GeometryTolerance.DEFAULT));
    }
    private static double longitudeForMeters(double meters) { return Math.toDegrees(meters / com.polyzones.core.geo.Haversine.EARTH_RADIUS_METERS); }
}
