package com.polyzones.core.trip;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

public record TripSummary(Map<String, Double> metersByZoneKey, double totalMeters,
                          Map<String, BigDecimal> roundedKilometersByZoneKey, BigDecimal roundedTotalKilometers) {
    public TripSummary {
        metersByZoneKey = Collections.unmodifiableMap(new TreeMap<>(metersByZoneKey));
        roundedKilometersByZoneKey = Collections.unmodifiableMap(new TreeMap<>(roundedKilometersByZoneKey));
    }
}
