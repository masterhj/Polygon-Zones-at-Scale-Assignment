package com.polyzones.service.web.dto;

import java.time.Instant;
import java.util.UUID;

public record TripEventResponse(UUID eventId, UUID zoneId, int zoneVersion, long zoneVersionSetVersion,
                                long evaluatedSetVersion, long ordinal, String kind, Instant eventTime,
                                Double dwellSeconds, String reason) { }
