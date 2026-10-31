package com.polyzones.core.index;

import static org.assertj.core.api.Assertions.assertThat;

import com.polyzones.core.geo.GeometryTolerance;
import com.polyzones.core.geo.LonLat;
import com.polyzones.core.shape.PolygonShape;
import com.polyzones.core.zone.Zone;
import com.polyzones.core.zone.ZoneKey;
import com.polyzones.core.zone.ZoneType;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ZoneSnapshotDiffTest {
    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final ZoneKey A = key(1), B = key(2), C = key(3);

    @Test
    @DisplayName("R1.4: snapshot diff reports added, changed and removed zone ids")
    void detectsIdentityAndContentChanges() {
        ZoneSnapshot previous = SnapshotBuilder.build(TENANT, 1, List.of(zone(A, 1, 0), zone(B, 1, 0)));
        ZoneSnapshot next = SnapshotBuilder.build(TENANT, 2, List.of(zone(A, 2, 1), zone(C, 1, 0)));

        ZoneSnapshotDiff diff = next.diff(previous);

        assertThat(diff.added()).isEqualTo(Set.of(C));
        assertThat(diff.changed()).isEqualTo(Set.of(A));
        assertThat(diff.removed()).isEqualTo(Set.of(B));
    }

    @Test
    @DisplayName("R1.4: initial snapshot diff treats all zones as additions")
    void initialDiff() {
        ZoneSnapshot snapshot = SnapshotBuilder.build(TENANT, 1, List.of(zone(A, 1, 0)));
        assertThat(snapshot.diff(null).added()).containsExactly(A);
    }

    private static Zone zone(ZoneKey key, int version, int priority) {
        double shift = priority == 0 ? 0 : 0.5;
        PolygonShape shape = PolygonShape.of(List.of(new LonLat(shift, 0), new LonLat(shift + 1, 0),
                new LonLat(shift + 1, 1), new LonLat(shift, 1), new LonLat(shift, 0)), List.of(), GeometryTolerance.DEFAULT);
        return new Zone(key, version, ZoneType.CITY, priority, shape);
    }
    private static ZoneKey key(long value) { return new ZoneKey(new UUID(0, value)); }
}
