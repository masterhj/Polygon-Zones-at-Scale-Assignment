package com.polyzones.service.web.dto;

import java.util.List;

public record PointQueryResponse(long setVersion, List<ZoneHitResponse> zones) {
    public PointQueryResponse { zones = List.copyOf(zones); }
}
