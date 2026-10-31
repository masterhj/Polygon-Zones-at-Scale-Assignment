package com.polyzones.core.trip;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Collections;

/** Largest-remainder allocation of displayed kilometres to three decimals (D-04). */
public final class RoundingAllocator {
    private RoundingAllocator() { }
    public static Allocation allocate(Map<String, Double> metersByKey) {
        if (metersByKey == null) throw new IllegalArgumentException("Distance parts are required");
        var parts = new ArrayList<Part>();
        BigDecimal totalKm = BigDecimal.ZERO;
        BigDecimal floors = BigDecimal.ZERO;
        for (var entry : metersByKey.entrySet()) {
            double meters = entry.getValue();
            if (entry.getKey() == null || !Double.isFinite(meters) || meters < 0) throw new IllegalArgumentException("Distance parts must be finite and non-negative");
            BigDecimal exactKm = BigDecimal.valueOf(meters).movePointLeft(3);
            BigDecimal floorKm = exactKm.setScale(3, RoundingMode.DOWN);
            parts.add(new Part(entry.getKey(), floorKm, exactKm.subtract(floorKm)));
            totalKm = totalKm.add(exactKm);
            floors = floors.add(floorKm);
        }
        BigDecimal roundedTotal = totalKm.setScale(3, RoundingMode.HALF_UP);
        int units = roundedTotal.subtract(floors).movePointRight(3).intValueExact();
        parts.sort(Comparator.comparing(Part::remainder).reversed().thenComparing(Part::key));
        for (int i = 0; i < units && !parts.isEmpty(); i++) parts.get(i % parts.size()).allocated = parts.get(i % parts.size()).allocated.add(new BigDecimal("0.001"));
        parts.sort(Comparator.comparing(Part::key));
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        for (Part part : parts) result.put(part.key, part.allocated);
        BigDecimal check = result.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (check.compareTo(roundedTotal) != 0) throw new IllegalStateException("Rounded distance parts do not equal rounded total");
        return new Allocation(Collections.unmodifiableMap(result), roundedTotal);
    }

    private static final class Part {
        private final String key;
        private BigDecimal allocated;
        private final BigDecimal remainder;
        private Part(String key, BigDecimal allocated, BigDecimal remainder) { this.key = key; this.allocated = allocated; this.remainder = remainder; }
        String key() { return key; }
        BigDecimal remainder() { return remainder; }
    }
    public record Allocation(Map<String, BigDecimal> kilometersByKey, BigDecimal roundedTotalKilometers) { }
}
