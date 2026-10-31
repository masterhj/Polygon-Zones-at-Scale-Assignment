package com.polyzones.service.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class LoginThrottleTest {
    @Test void appliesSlidingFailureWindowAndSuccessClearsIt() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-31T00:00:00Z"));
        LoginThrottle throttle = new LoginThrottle(clock, 5, Duration.ofMinutes(5), 100);
        var key = throttle.key("tenant", "user", "127.0.0.1");
        for (int i = 0; i < 4; i++) throttle.failed(key);
        assertThat(throttle.blocked(key)).isFalse();
        throttle.failed(key);
        assertThat(throttle.blocked(key)).isTrue();
        clock.advance(Duration.ofMinutes(4));
        throttle.failed(key);
        assertThat(throttle.blocked(key)).isTrue();
        clock.advance(Duration.ofMinutes(2));
        assertThat(throttle.blocked(key)).isFalse();
        throttle.failed(key);
        throttle.succeeded(key);
        assertThat(throttle.blocked(key)).isFalse();
    }

    @Test void hashesTenantAndUsernameButKeepsAddressAsPartOfThrottleKey() {
        LoginThrottle throttle = new LoginThrottle(Clock.systemUTC(), 5, Duration.ofMinutes(5), 100);
        var one = throttle.key("acme", "alice@example.test", "192.0.2.1");
        var same = throttle.key("acme", "alice@example.test", "192.0.2.2");
        assertThat(one.tenantHash()).doesNotContain("acme");
        assertThat(one.usernameHash()).doesNotContain("alice", "example");
        assertThat(one.usernameHash()).isEqualTo(same.usernameHash());
        assertThat(one.ipAddress()).isNotEqualTo(same.ipAddress());
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant;
        private MutableClock(Instant initial) { this.instant = new AtomicReference<>(initial); }
        void advance(Duration amount) { instant.updateAndGet(value -> value.plus(amount)); }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { throw new UnsupportedOperationException(); }
        @Override public Instant instant() { return instant.get(); }
    }
}
