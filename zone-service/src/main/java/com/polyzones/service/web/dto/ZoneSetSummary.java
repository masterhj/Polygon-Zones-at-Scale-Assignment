package com.polyzones.service.web.dto;

import java.time.Instant;

public record ZoneSetSummary(long version, String status, Instant createdAt, Instant publishedAt) { }
