package com.polyzones.service.web.dto;

import java.time.Instant;
import java.util.UUID;

public record TripResponse(UUID tripId, UUID employeeId, long pinnedSetVersion, String status,
                           Instant startedAt, Instant completedAt, long durableAckedSeq) { }
