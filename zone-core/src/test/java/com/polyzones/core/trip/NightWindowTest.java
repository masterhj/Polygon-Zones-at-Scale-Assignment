package com.polyzones.core.trip;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NightWindowTest {
    private final NightWindow window = new NightWindow(LocalTime.of(22, 0), LocalTime.of(6, 0));

    @Test
    @DisplayName("D-07: night is [22:00, 06:00) in the tenant's IANA time zone")
    void inclusiveStartExclusiveEndAndWrap() {
        ZoneId india = ZoneId.of("Asia/Kolkata");
        assertThat(window.contains(Instant.parse("2026-10-31T16:30:00Z"), india)).isTrue();
        assertThat(window.contains(Instant.parse("2026-10-30T18:30:00Z"), india)).isTrue();
        assertThat(window.contains(Instant.parse("2026-10-31T16:29:59Z"), india)).isFalse();
        assertThat(window.contains(Instant.parse("2026-10-31T00:30:00Z"), india)).isFalse();
    }

    @Test
    @DisplayName("D-07: DST gaps and repeated local times are evaluated by ZonedDateTime")
    void handlesDstTransitionInstants() {
        ZoneId newYork = ZoneId.of("America/New_York");
        assertThat(window.contains(Instant.parse("2026-11-01T05:30:00Z"), newYork)).isTrue(); // 01:30 before fallback
        assertThat(window.contains(Instant.parse("2026-11-01T06:30:00Z"), newYork)).isTrue(); // 01:30 after fallback
        assertThat(window.contains(Instant.parse("2026-03-08T07:30:00Z"), newYork)).isTrue(); // 03:30 after spring gap, before 06:00
        assertThat(window.contains(Instant.parse("2026-03-08T10:30:00Z"), newYork)).isFalse(); // 06:30 after the transition
    }
}
