package com.polyzones.service.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public record PingRequest(@NotNull @Size(min = 1, max = 500) List<@NotNull @Valid PingItem> pings) {
    public record PingItem(@PositiveOrZero long seq, @NotNull Instant eventTime,
                           @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") Double lat,
                           @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") Double lon,
                           @DecimalMin("0.0") Double accuracyMeters) { }
}
