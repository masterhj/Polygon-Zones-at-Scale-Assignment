package com.polyzones.core.trip;

import com.polyzones.core.zone.ZoneKey;
import java.time.Instant;
import java.util.UUID;

public record AlertCandidate(UUID alertId, UUID sourceEventId, UUID tenantId, UUID tripId, ZoneKey zoneId,
                             long evaluatedSetVersion, String kind, Instant createdAt) { }
