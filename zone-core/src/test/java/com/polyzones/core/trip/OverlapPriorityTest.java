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
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OverlapPriorityTest {
    @Test
    @DisplayName("D-06 / R3.1: overlap distance is billed once to the highest-priority tariff zone")
    void airportPriorityWinsAndDistanceLedgerBalances() {
        UUID tenant = new UUID(0, 71), trip = new UUID(0, 72);
        Zone city = zone(1, 0, .1, ZoneType.CITY, 0);
        Zone airport = zone(2, .04, .08, ZoneType.AIRPORT, 10);
        ZoneSnapshot snapshot = SnapshotBuilder.build(tenant, 1, List.of(city, airport));
        List<SubSegment> segments = SegmentSplitter.split(snapshot, new LonLat(.02, .05), new LonLat(.09, .05));
        double airportMeters = segments.stream().filter(segment -> segment.billingZoneOrdinal() == snapshot.ordinalById().get(airport.id()))
                .mapToDouble(SubSegment::meters).sum();
        double total = segments.stream().mapToDouble(SubSegment::meters).sum();
        assertThat(airportMeters).isCloseTo(com.polyzones.core.geo.Haversine.distanceMeters(new LonLat(.04, .05), new LonLat(.08, .05)),
                org.assertj.core.data.Offset.offset(1e-6));
        assertThat(total).isCloseTo(com.polyzones.core.geo.Haversine.distanceMeters(new LonLat(.02, .05), new LonLat(.09, .05)),
                org.assertj.core.data.Offset.offset(1e-6));
        TripAttributor attributor = new TripAttributor(tenant, trip, snapshot);
        var start = java.time.Instant.EPOCH;
        attributor.apply(new Ping(trip, 0, start, new LonLat(.02, .05), null), snapshot,
                new NightWindow(java.time.LocalTime.of(22, 0), java.time.LocalTime.of(6, 0)), java.time.ZoneId.of("UTC"));
        attributor.apply(new Ping(trip, 1, start.plusSeconds(60), new LonLat(.09, .05), null), snapshot,
                new NightWindow(java.time.LocalTime.of(22, 0), java.time.LocalTime.of(6, 0)), java.time.ZoneId.of("UTC"));
        var invoice = attributor.complete(start.plusSeconds(120)).summary();
        assertThat(invoice.metersByZoneKey().get(airport.id().value().toString())).isCloseTo(airportMeters,
                org.assertj.core.data.Offset.offset(1e-6));
        assertThat(invoice.totalMeters()).isCloseTo(total, org.assertj.core.data.Offset.offset(1e-6));
    }

    private static Zone zone(long id, double minLon, double maxLon, ZoneType type, int priority) {
        ZoneKey key = new ZoneKey(new UUID(0, id));
        PolygonShape shape = PolygonShape.of(List.of(new LonLat(minLon, 0), new LonLat(maxLon, 0),
                new LonLat(maxLon, .1), new LonLat(minLon, .1), new LonLat(minLon, 0)), List.of(), GeometryTolerance.DEFAULT);
        return new Zone(key, 1, type, priority, shape);
    }
}
