package com.polyzones.service.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.TimeUnit;

/** Bounded sliding-window failure counter keyed by hashed tenant and username plus remote IP. */
public final class LoginThrottle {
    private final Cache<Key, Window> failures;
    private final Clock clock;
    private final int threshold;
    private final Duration window;

    public LoginThrottle(Clock clock, int threshold, Duration window, long maxKeys) {
        if (clock == null || threshold < 1 || window == null || window.isNegative() || window.isZero() || maxKeys < 1)
            throw new IllegalArgumentException("Invalid login throttle configuration");
        this.clock = clock; this.threshold = threshold; this.window = window;
        this.failures = Caffeine.newBuilder().maximumSize(maxKeys).expireAfterAccess(window).build();
    }

    public boolean blocked(Key key) {
        Window value = failures.getIfPresent(key);
        if (value == null) return false;
        synchronized (value) { discardExpired(value, clock.instant()); return value.failures.size() >= threshold; }
    }

    public void failed(Key key) {
        Instant now = clock.instant();
        Window value = failures.get(key, ignored -> new Window());
        synchronized (value) {
            discardExpired(value, now);
            value.failures.addLast(now);
            failures.put(key, value);
        }
    }

    public void succeeded(Key key) { failures.invalidate(key); }

    public Key key(String tenant, String username, String ipAddress) {
        return new Key(hash(tenant == null ? "<unknown-tenant>" : tenant), hash(username), ipAddress == null ? "<unknown-ip>" : ipAddress);
    }

    private void discardExpired(Window value, Instant now) {
        Instant cutoff = now.minus(window);
        while (!value.failures.isEmpty() && !value.failures.peekFirst().isAfter(cutoff)) value.failures.removeFirst();
    }

    private static String hash(String value) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest((value == null ? "<null>" : value).getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable", impossible); }
    }

    public record Key(String tenantHash, String usernameHash, String ipAddress) { }
    private static final class Window { private final Deque<Instant> failures = new ArrayDeque<>(); }
}
