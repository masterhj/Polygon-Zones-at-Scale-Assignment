package com.polyzones.service.application;

import com.polyzones.core.geo.LonLat;
import com.polyzones.core.trip.Ping;
import com.polyzones.service.web.dto.PingRequest;
import com.polyzones.service.web.dto.TripResponse;
import java.time.Instant;
import java.util.UUID;

/** Pure mapping between validated API records, trip core values and response records. */
public final class TripMapper {
    public Ping toPing(UUID tripId, PingRequest.PingItem item) {
        return new Ping(tripId, item.seq(), item.eventTime(), new LonLat(item.lon(), item.lat()), item.accuracyMeters());
    }
    public TripResponse toResponse(UUID tripId, UUID employeeId, long pinnedSetVersion, String status,
                                   Instant startedAt, Instant completedAt, long durableAckedSeq) {
        return new TripResponse(tripId, employeeId, pinnedSetVersion, status, startedAt, completedAt, durableAckedSeq);
    }
}
