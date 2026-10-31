package com.polyzones.service.web.dto;

import java.time.Instant;
import java.util.UUID;

public record AlertResponse(UUID alertId, UUID tripId, UUID zoneId, String kind, String severity,
                            Instant eventTime, boolean acknowledged) { }
