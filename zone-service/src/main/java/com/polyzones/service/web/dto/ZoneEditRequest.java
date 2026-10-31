package com.polyzones.service.web.dto;

import com.polyzones.core.zone.ZoneType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record ZoneEditRequest(UUID zoneId, @NotNull ZoneType type, int priority,
                              @NotNull @Valid PolygonDto geometry) { }
