package com.polyzones.service.web.dto;

import com.polyzones.core.zone.ZoneType;
import java.util.UUID;

public record ZoneResponse(UUID zoneId, int version, ZoneType type, int priority, long setVersion,
                           PolygonDto geometry) { }
