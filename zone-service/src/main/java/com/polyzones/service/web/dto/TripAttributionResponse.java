package com.polyzones.service.web.dto;

import java.math.BigDecimal;
import java.util.Map;

public record TripAttributionResponse(Map<String, Double> metersByZone, double totalMeters,
                                     Map<String, BigDecimal> kilometersByZone,
                                     BigDecimal roundedTotalKilometers, String method, long pinnedSetVersion) {
    public TripAttributionResponse { metersByZone = Map.copyOf(metersByZone); kilometersByZone = Map.copyOf(kilometersByZone); }
}
