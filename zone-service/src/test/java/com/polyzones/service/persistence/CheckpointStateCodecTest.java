package com.polyzones.service.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.polyzones.core.geo.LonLat;
import com.polyzones.core.trip.OpenMembership;
import com.polyzones.core.trip.SkippedRange;
import com.polyzones.core.trip.TripStateCheckpoint;
import com.polyzones.core.zone.ZoneKey;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class CheckpointStateCodecTest {
    @Test void roundTripsEveryTripStateFieldWithoutUsingDomainObjectsAsJsonMapKeys() {
        UUID tenant = new UUID(0, 1), trip = new UUID(0, 2);
        ZoneKey zone = new ZoneKey(new UUID(0, 3));
        OpenMembership membership = new OpenMembership(zone, 4, 6, 7, Instant.parse("2026-10-31T20:00:00Z"), 12);
        TripStateCheckpoint state = new TripStateCheckpoint(tenant, trip, 6, new LonLat(77.5, 12.9),
                Instant.parse("2026-10-31T20:01:00Z"), 12, 12, 9, 1, 2, 3, 4, 123.5, 7,
                List.of(new SkippedRange(3, 4), new SkippedRange(8, 8)), Map.of(zone.value().toString(), 100d),
                Map.of(zone, membership), Map.of(), false);
        CheckpointStateCodec codec = new CheckpointStateCodec(JsonMapper.builder().build());
        String json = codec.encode(state);
        assertThat(codec.decode(json)).isEqualTo(state);
    }
}
