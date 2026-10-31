package com.polyzones.service.web.dto;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

public record TripStartRequest(UUID tripId, @NotNull UUID employeeId, @NotNull Instant startTime) { }
