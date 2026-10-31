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
import java.util.List;
import java.util.ArrayList;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TripAttributionPropertyTest {
    private static final UUID TENANT = new UUID(0, 7), TRIP = new UUID(0, 8);
    private static final long SEED = 0x5eed_2026L;

    @Test
    @DisplayName("R3.1 / D-04: seeded straight-path batching preserves distance ledger and total")
    void batchingInvariantAndDistanceConservation() {
        ZoneSnapshot snapshot = SnapshotBuilder.build(TENANT, 1, List.of(zone()));
        TripSummary single = run(snapshot, 1);
        for (int points : new int[]{2, 3, 7, 64, 257}) {
            TripSummary batched = run(snapshot, points);
            assertThat(batched.metersByZoneKey().keySet()).containsExactlyInAnyOrderElementsOf(single.metersByZoneKey().keySet());
            single.metersByZoneKey().forEach((key, meters) ->
                    assertThat(batched.metersByZoneKey().get(key)).isCloseTo(meters, org.assertj.core.data.Offset.offset(1e-6)));
            assertThat(batched.totalMeters()).isCloseTo(single.totalMeters(), org.assertj.core.data.Offset.offset(1e-6));
            double ledgerSum = batched.metersByZoneKey().values().stream().mapToDouble(Double::doubleValue).sum();
            assertThat(ledgerSum).isCloseTo(batched.totalMeters(), org.assertj.core.data.Offset.offset(1e-6));
        }
    }

    private static TripSummary run(ZoneSnapshot snapshot, int pieces) {
        TripAttributor attributor = new TripAttributor(TENANT, TRIP, snapshot);
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        Random random = new Random(SEED ^ pieces);
        List<Double> points = new ArrayList<>(pieces + 1);
        points.add(0d);
        for (int i = 1; i < pieces; i++) points.add(random.nextDouble());
        points.add(1d);
        points.subList(1, points.size() - 1).sort(Double::compare);
        for (int i = 0; i < points.size(); i++) {
            attributor.apply(new Ping(TRIP, i, start.plusSeconds(i), new LonLat(points.get(i), 0), null), snapshot,
                    new NightWindow(LocalTime.of(22, 0), LocalTime.of(6, 0)), ZoneId.of("UTC"));
        }
        return attributor.complete(start.plusSeconds(pieces + 1L)).summary();
    }

    private static Zone zone() {
        ZoneKey id = new ZoneKey(new UUID(0, 9));
        List<LonLat> ring = List.of(new LonLat(.25, -.1), new LonLat(.75, -.1), new LonLat(.75, .1),
                new LonLat(.25, .1), new LonLat(.25, -.1));
        return new Zone(id, 1, ZoneType.CITY, 0, PolygonShape.of(ring, List.of(), GeometryTolerance.DEFAULT));
    }
}
