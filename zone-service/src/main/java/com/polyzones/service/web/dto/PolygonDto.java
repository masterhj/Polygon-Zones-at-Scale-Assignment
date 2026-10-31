package com.polyzones.service.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** GeoJSON Polygon shape with longitude first. */
public record PolygonDto(@NotBlank String type,
                         @NotNull @Size(min = 1, max = 257) List<@NotNull List<@NotNull List<@NotNull Double>>> coordinates) { }
