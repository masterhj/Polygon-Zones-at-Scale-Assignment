package com.polyzones.service.publish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

class PinnedSnapshotRegistryTest {
    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    @DisplayName("R1.5 / D-09: pinned snapshots remain strongly reachable until the final release")
    void referenceCountsRetainThenCacheSnapshot() {
        SnapshotRegistry latest = new SnapshotRegistry();
        ZoneSnapshot version1 = snapshot(1);
        ZoneSnapshot version2 = snapshot(2);
        latest.publish(TENANT, version2);
        PinnedSnapshotRegistry pins = new PinnedSnapshotRegistry(latest, new SnapshotLoader() {
            @Override public ZoneSnapshot loadPublished(UUID tenant, long version) { return version == 1 ? version1 : null; }
            @Override public ZoneSnapshot loadCandidate(UUID tenant, long version) { throw new UnsupportedOperationException(); }
        }, 100);

        assertThat(pins.acquire(TENANT, 1)).isSameAs(version1);
        assertThat(pins.acquire(TENANT, 1)).isSameAs(version1);
        pins.release(TENANT, 1);
        assertThat(pins.pinnedSnapshotCount()).isEqualTo(1);
        pins.release(TENANT, 1);
        assertThat(pins.pinnedSnapshotCount()).isZero();
        assertThat(pins.acquire(TENANT, 1)).isSameAs(version1);
        pins.release(TENANT, 1);
    }

    @Test
    @DisplayName("R1.5: unknown, non-published history cannot be pinned")
    void missingSnapshotIsNotFound() {
        PinnedSnapshotRegistry pins = new PinnedSnapshotRegistry(new SnapshotRegistry(), new SnapshotLoader() {
            @Override public ZoneSnapshot loadPublished(UUID tenant, long version) { return null; }
            @Override public ZoneSnapshot loadCandidate(UUID tenant, long version) { throw new UnsupportedOperationException(); }
        }, 100);
        assertThatThrownBy(() -> pins.acquire(TENANT, 9)).isInstanceOf(PinnedSnapshotRegistry.VersionNotFoundException.class);
    }

    private static ZoneSnapshot snapshot(int version) {
        Zone zone = new Zone(new ZoneKey(UUID.nameUUIDFromBytes(("zone-" + version).getBytes())), version,
                ZoneType.CITY, 0, PolygonShape.of(List.of(new LonLat(0, 0), new LonLat(1, 0),
                new LonLat(1, 1), new LonLat(0, 1), new LonLat(0, 0)), List.of(), GeometryTolerance.DEFAULT));
        return SnapshotBuilder.build(TENANT, version, List.of(zone));
    }
}
