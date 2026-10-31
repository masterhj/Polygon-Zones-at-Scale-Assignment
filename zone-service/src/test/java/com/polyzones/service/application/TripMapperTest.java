package com.polyzones.service.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TripMapperTest {
    private final TripMapper mapper = new TripMapper();

    @Test void mapsValidatedPingAndTripResponseWithoutLosingTenantIndependentFields() {
        UUID tripId = UUID.randomUUID();
        Instant time = Instant.parse("2026-10-31T00:00:00Z");
        var ping = mapper.toPing(tripId, new com.polyzones.service.web.dto.PingRequest.PingItem(7, time, 12.5, 77.5, 3.0));
        assertThat(ping.tripId()).isEqualTo(tripId);
        assertThat(ping.location().lat()).isEqualTo(12.5);
        assertThat(ping.location().lon()).isEqualTo(77.5);
        var response = mapper.toResponse(tripId, UUID.randomUUID(), 9, "IN_PROGRESS", time, null, 6);
        assertThat(response.pinnedSetVersion()).isEqualTo(9);
        assertThat(response.durableAckedSeq()).isEqualTo(6);
        assertThat(response.startedAt()).isEqualTo(time);
    }
}
