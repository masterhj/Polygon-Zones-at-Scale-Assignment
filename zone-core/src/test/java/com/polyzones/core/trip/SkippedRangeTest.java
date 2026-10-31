package com.polyzones.core.trip;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SkippedRangeTest {
    @Test void mergesOnlyOverlappingOrAdjacentIntervalsAndPreservesDisjointGaps() {
        TripState state = new TripState(new UUID(0, 1), new UUID(0, 2), 1);
        state.addSkippedRange(10, 12);
        state.addSkippedRange(20, 22);
        state.addSkippedRange(13, 19);
        assertThat(state.skippedRanges()).containsExactly(new SkippedRange(10, 22));
        state.addSkippedRange(30, 31);
        assertThat(state.skippedRanges()).containsExactly(new SkippedRange(10, 22), new SkippedRange(30, 31));
        assertThat(state.gapsSkipped()).isEqualTo(4);
        assertThat(state.wasSkipped(15)).isTrue();
        assertThat(state.wasSkipped(25)).isFalse();
    }

    @Test void rangeMergeHandlesLongMaxWithoutOverflow() {
        assertThat(SkippedRange.merge(List.of(new SkippedRange(Long.MAX_VALUE - 2, Long.MAX_VALUE - 1)),
                new SkippedRange(Long.MAX_VALUE, Long.MAX_VALUE)))
                .containsExactly(new SkippedRange(Long.MAX_VALUE - 2, Long.MAX_VALUE));
    }
}
