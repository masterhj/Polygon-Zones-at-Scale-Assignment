package com.polyzones.service.admin;

import com.polyzones.core.geo.GeometryTolerance;
import com.polyzones.core.geo.LonLat;
import com.polyzones.core.shape.PolygonShape;
import com.polyzones.core.zone.Zone;
import com.polyzones.core.zone.ZoneKey;
import com.polyzones.core.zone.ZoneType;
import com.polyzones.service.publish.ZoneEditCommand;
import com.polyzones.service.publish.ZoneSetPublisher;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;

/** Seeds disposable demo credentials and a sample Bengaluru zone set only when the demo profile is active. */
@Component
@Profile("demo")
@Order(Ordered.LOWEST_PRECEDENCE - 10)
public final class DemoDataSeeder implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final ZoneSetPublisher publisher;
    private final String demoPassword;

    public DemoDataSeeder(JdbcTemplate jdbc, PasswordEncoder passwords, ZoneSetPublisher publisher,
                          @Value("${DEMO_PASSWORD:DemoOnly-ChangeMe-2026}") String demoPassword) {
        this.jdbc = jdbc; this.passwords = passwords; this.publisher = publisher; this.demoPassword = demoPassword;
    }

    @Override public void run(ApplicationArguments args) {
        for (String tenantName : List.of("acme", "globex")) seedTenant(tenantName);
    }

    private void seedTenant(String tenantName) {
        jdbc.update("insert into tenants(id,name) values (?,?) on conflict(name) do nothing", id(tenantName), tenantName);
        UUID tenantId = jdbc.queryForObject("select id from tenants where name=?", UUID.class, tenantName);
        for (String role : List.of("ADMIN", "OPERATOR", "EMPLOYEE")) {
            String username = role.toLowerCase(java.util.Locale.ROOT);
            jdbc.update("insert into app_users(id,tenant_id,username,password_hash,role) values (?,?,?,?,?) on conflict(tenant_id,username) do nothing",
                    id(tenantName + ":" + username), tenantId, username, passwords.encode(demoPassword), role);
        }
        Long sets = jdbc.queryForObject("select count(*) from zone_set_versions where tenant_id=? and status='PUBLISHED'", Long.class, tenantId);
        if (sets == null || sets == 0) {
            String bodyHash = java.util.HexFormat.of().formatHex(sha256((tenantName + ":demo-zones-v1").getBytes(StandardCharsets.UTF_8)));
            publisher.publish(tenantId, new ZoneEditCommand(zones(tenantId), List.of(), "demo-zones-v1", bodyHash), Duration.ofMinutes(2));
        }
    }

    private static List<Zone> zones(UUID tenantId) {
        return List.of(
                zone(tenantId, "city", ZoneType.CITY, 10,
                        polygon(ring(77.40, 12.80, 77.62, 13.02))),
                zone(tenantId, "airport", ZoneType.AIRPORT, 20,
                        polygon(ring(77.53, 12.90, 77.60, 12.97), ring(77.55, 12.92, 77.57, 12.94))),
                zone(tenantId, "tariff", ZoneType.TARIFF, 5,
                        polygon(ring(77.45, 12.85, 77.52, 12.91))),
                zone(tenantId, "no-go", ZoneType.NO_GO, 0,
                        polygon(ring(77.48, 12.96, 77.50, 12.98))));
    }

    private static Zone zone(UUID tenant, String name, ZoneType type, int priority, PolygonShape shape) {
        return new Zone(new ZoneKey(id(tenant + ":" + name)), 1, type, priority, shape);
    }

    private static PolygonShape polygon(List<LonLat> outer, List<LonLat>... holes) {
        return PolygonShape.of(outer, List.of(holes), GeometryTolerance.DEFAULT);
    }
    private static List<LonLat> ring(double minLon, double minLat, double maxLon, double maxLat) {
        return List.of(new LonLat(minLon, minLat), new LonLat(maxLon, minLat), new LonLat(maxLon, maxLat),
                new LonLat(minLon, maxLat), new LonLat(minLon, minLat));
    }
    private static UUID id(String value) { return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)); }
    private static byte[] sha256(byte[] value) {
        try { return java.security.MessageDigest.getInstance("SHA-256").digest(value); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable", impossible); }
    }
}
