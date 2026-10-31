package com.polyzones.core.trip;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RoundingAllocatorTest {
    @Test
    @DisplayName("D-04: largest-remainder output preserves airport invoice totals")
    void airportDropRoundingSumsToRoundedTotal() {
        var allocation = RoundingAllocator.allocate(Map.of("city", 30_400d, "airport", 11_600d));
        assertThat(allocation.kilometersByKey()).containsEntry("city", new BigDecimal("30.400"))
                .containsEntry("airport", new BigDecimal("11.600"));
        assertThat(allocation.roundedTotalKilometers()).isEqualByComparingTo("42.000");
    }

    @Test
    @DisplayName("D-04: equal remainders are distributed by stable lexical key")
    void tieBreakIsDeterministic() {
        var allocation = RoundingAllocator.allocate(Map.of("c", .5d, "b", .5d, "a", .5d));
        assertThat(allocation.kilometersByKey()).containsEntry("a", new BigDecimal("0.001"))
                .containsEntry("b", new BigDecimal("0.001")).containsEntry("c", new BigDecimal("0.000"));
        assertThat(allocation.roundedTotalKilometers()).isEqualByComparingTo("0.002");
    }
}
