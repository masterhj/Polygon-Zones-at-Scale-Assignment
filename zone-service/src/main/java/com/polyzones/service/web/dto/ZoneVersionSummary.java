package com.polyzones.service.web.dto;

import java.time.Instant;

public record ZoneVersionSummary(int version, long setVersion, boolean deleted, Instant createdAt) { }
