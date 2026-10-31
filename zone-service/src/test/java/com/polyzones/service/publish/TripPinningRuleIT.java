package com.polyzones.service.publish;

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

class TripPinningRuleIT {
    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    @DisplayName("R4.2 / D-09: an in-progress trip retains its billing snapshot across a live swap")
    void pinnedTripAndNewTripSeeTheirDefinedVersions() {
        SnapshotRegistry latest = new SnapshotRegistry();
        ZoneSnapshot v1 = snapshot(1, 0);
        ZoneSnapshot v2 = snapshot(2, 10);
        latest.publish(TENANT, v1);
        PinnedSnapshotRegistry pins = new PinnedSnapshotRegistry(latest, new SnapshotLoader() {
            @Override public ZoneSnapshot loadPublished(UUID tenant, long version) { return version == 1 ? v1 : v2; }
            @Override public ZoneSnapshot loadCandidate(UUID tenant, long version) { throw new UnsupportedOperationException(); }
        }, 100);

        ZoneSnapshot tripBillingSnapshot = pins.acquire(TENANT, latest.latest(TENANT).setVersion());
        latest.publish(TENANT, v2);
        ZoneSnapshot nextTripSnapshot = pins.acquire(TENANT, latest.latest(TENANT).setVersion());

        assertThat(tripBillingSnapshot.setVersion()).isEqualTo(1);
        assertThat(nextTripSnapshot.setVersion()).isEqualTo(2);
        pins.release(TENANT, 1);
        pins.release(TENANT, 2);
    }

    private static ZoneSnapshot snapshot(int version, double minLon) {
        PolygonShape shape = PolygonShape.of(List.of(new LonLat(minLon, 0), new LonLat(minLon + 1, 0),
                new LonLat(minLon + 1, 1), new LonLat(minLon, 1), new LonLat(minLon, 0)), List.of(), GeometryTolerance.DEFAULT);
        Zone zone = new Zone(new ZoneKey(UUID.nameUUIDFromBytes(("pin-" + version).getBytes())), version,
                ZoneType.CITY, 0, shape);
        return SnapshotBuilder.build(TENANT, version, List.of(zone));
    }
}
