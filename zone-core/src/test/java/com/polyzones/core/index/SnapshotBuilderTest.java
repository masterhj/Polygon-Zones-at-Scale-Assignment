package com.polyzones.core.index;

import static org.assertj.core.api.Assertions.assertThat;
import com.polyzones.core.geo.GeometryTolerance;
import com.polyzones.core.geo.LonLat;
import com.polyzones.core.shape.PolygonShape;
import com.polyzones.core.zone.Zone;
import com.polyzones.core.zone.ZoneKey;
import com.polyzones.core.zone.ZoneType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SnapshotBuilderTest {
    @Test void snapshotIsDefensiveDeterministicAndUsesInjectedClock() {
        Zone b=zone("00000000-0000-0000-0000-000000000002"),a=zone("00000000-0000-0000-0000-000000000001");
        Zone[] input={b,a}; Instant now=Instant.parse("2026-10-31T00:00:00Z");
        ZoneSnapshot snapshot=SnapshotBuilder.build(UUID.randomUUID(),7,List.of(input),GeometryTolerance.DEFAULT,Clock.fixed(now, ZoneOffset.UTC),16);
        input[0]=null; Zone[] exposed=snapshot.zones(); exposed[0]=null;
        assertThat(snapshot.zoneAt(0).id()).isEqualTo(a.id()); assertThat(snapshot.builtAt()).isEqualTo(now);
        assertThat(snapshot.ordinalById().get(b.id())).isEqualTo(1); assertThat(snapshot.buildStats().vertexCount()).isEqualTo(8);
    }
    @Test void indexFactoryCanSelectTheIndependentNaiveImplementation() {
        Zone zone = zone("00000000-0000-0000-0000-000000000003");
        ZoneSnapshot snapshot = SnapshotBuilder.build(UUID.randomUUID(), 1, List.of(zone), GeometryTolerance.DEFAULT,
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), 16,
                (zones, tolerance, capacity) -> new NaiveZoneIndex(zones, tolerance));
        assertThat(snapshot.index()).isInstanceOf(NaiveZoneIndex.class);
        assertThat(snapshot.index().query(.0005, .0005)).hasSize(1);
    }
    private static Zone zone(String id) { return new Zone(new ZoneKey(UUID.fromString(id)),1,ZoneType.CITY,0,PolygonShape.of(List.of(new LonLat(0,0),new LonLat(.001,0),new LonLat(.001,.001),new LonLat(0,.001),new LonLat(0,0)),List.of(),GeometryTolerance.DEFAULT)); }
}
