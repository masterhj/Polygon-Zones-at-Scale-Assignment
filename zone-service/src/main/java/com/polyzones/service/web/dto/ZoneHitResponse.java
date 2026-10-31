package com.polyzones.service.web.dto;

import com.polyzones.core.geo.Location;
import com.polyzones.core.zone.ZoneType;
import java.util.UUID;

public record ZoneHitResponse(UUID zoneId, int version, ZoneType type, int priority, Location location) { }
