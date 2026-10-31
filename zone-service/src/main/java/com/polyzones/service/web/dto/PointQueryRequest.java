package com.polyzones.service.web.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record PointQueryRequest(@NotNull @DecimalMin("-90.0") @DecimalMax("90.0") Double lat,
                                @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") Double lon,
                                @PositiveOrZero Long asOfSetVersion) { }
