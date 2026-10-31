package com.polyzones.service.publish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.polyzones.core.geo.GeometryTolerance;
import com.polyzones.core.geo.LonLat;
import com.polyzones.core.index.ZoneSnapshot;
import com.polyzones.core.shape.PolygonShape;
import com.polyzones.core.zone.Zone;
import com.polyzones.core.zone.ZoneKey;
import com.polyzones.core.zone.ZoneType;
import com.polyzones.service.persistence.JdbcZoneSetStore;
import com.polyzones.service.persistence.GeometryCodec;
import com.polyzones.service.observability.ZoneVersionGaugeRegistry;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.springframework.test.context.ActiveProfiles;

@Testcontainers
@SpringBootTest(classes = com.polyzones.service.ZoneServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class ZonePublicationIT {
    @Container @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18");
    private static final UUID TENANT = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID IDEMPOTENCY_TENANT = UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final UUID ZONE = UUID.fromString("20000000-0000-0000-0000-000000000001");

    @Autowired JdbcTemplate jdbc;
    @Autowired ZoneSetPublisher publisher;
    @Autowired SnapshotLoader loader;
    @Autowired SnapshotRegistry registry;
    @Autowired GeometryCodec geometry;
    @Autowired ZoneVersionGaugeRegistry gauges;

    @BeforeEach void createTenant() {
        jdbc.update("insert into tenants(id,name) values (?,?) on conflict do nothing", TENANT, "phase4-test");
        jdbc.update("insert into tenants(id,name) values (?,?) on conflict do nothing", IDEMPOTENCY_TENANT, "phase4-idempotency-test");
    }

    @Test
    @DisplayName("R1.4 / R1.5 / D-13: DB-first edits publish monotonically and remain loadable after restart")
    void persistsAndRebuildsPublishedVersion() {
        var first = publisher.publish(TENANT, command(0, null, null), Duration.ofSeconds(10));
        var second = publisher.publish(TENANT, command(2, null, null), Duration.ofSeconds(10));
        assertThat(second.setVersion()).isGreaterThan(first.setVersion());
        assertThat(jdbc.queryForObject("select status from zone_set_versions where tenant_id=? and version=?", String.class, TENANT, first.setVersion())).isEqualTo("PUBLISHED");
        ZoneSnapshot old = loader.loadPublished(TENANT, first.setVersion());
        ZoneSnapshot current = loader.loadPublished(TENANT, second.setVersion());
        assertThat(old.index().query(0.25, 0.25)).hasSize(1);
        assertThat(current.index().query(0.25, 0.25)).isEmpty();

        Zone failedEdit = zone(0, 3);
        Zone buildingEdit = zone(10, 4);
        insertUnpublishedVersion(3, "FAILED", failedEdit);
        insertUnpublishedVersion(4, "BUILDING", buildingEdit);
        ZoneSnapshot candidate = loader.loadCandidate(TENANT, 4);
        assertThat(candidate.index().query(0.25, 0.25)).isEmpty();
        assertThat(candidate.index().query(10.25, 0.25)).hasSize(1);

        SnapshotRegistry restarted = new SnapshotRegistry();
        ApplicationArguments args = new DefaultApplicationArguments(new String[0]);
        new StartupSnapshotLoader(jdbc, loader, restarted, gauges).run(args);
        assertThat(restarted.latest(TENANT).setVersion()).isEqualTo(second.setVersion());
    }

    @Test
    @DisplayName("D-13: idempotent published edits replay the original result and reject body changes")
    void idempotencyIsTenantScopedAndBodyBound() {
        ZoneSetPublisher.PublishResult created = publisher.publish(IDEMPOTENCY_TENANT, command(0, "request-1", "hash-a"), Duration.ofSeconds(10));
        ZoneSetPublisher.PublishResult replayed = publisher.publish(IDEMPOTENCY_TENANT, command(0, "request-1", "hash-a"), Duration.ofSeconds(10));
        assertThat(replayed.setVersion()).isEqualTo(created.setVersion());
        assertThatThrownBy(() -> publisher.publish(IDEMPOTENCY_TENANT, command(0, "request-1", "hash-b"), Duration.ofSeconds(10)))
                .isInstanceOf(JdbcZoneSetStore.IdempotencyConflictException.class);
    }

    private static ZoneEditCommand command(double minLon, String key, String hash) {
        return new ZoneEditCommand(List.of(zone(minLon, 1)), List.of(), key, hash);
    }

    private void insertUnpublishedVersion(long setVersion, String status, Zone zone) {
        PolygonShape shape = zone.shape();
        var bounds = shape.bounds();
        String geoJson = geometry.encode(shape);
        jdbc.update("insert into zone_set_versions(tenant_id,version,status) values (?,?,?)", TENANT, setVersion, status);
        jdbc.update("""
                insert into zone_versions(tenant_id,zone_id,version,set_version,name,zone_type,priority,geometry_geojson,
                    vertex_count,min_lon,min_lat,max_lon,max_lat,content_hash,deleted)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,'fixture',false)
                """, TENANT, ZONE, zone.version(), setVersion, ZONE.toString(), zone.type().name(), zone.priority(), geoJson,
                shape.vertexCount(), bounds.minLon(), bounds.minLat(), bounds.maxLon(), bounds.maxLat());
    }

    private static Zone zone(double minLon, int version) {
        PolygonShape shape = PolygonShape.of(List.of(new LonLat(minLon, 0), new LonLat(minLon + 1, 0),
                new LonLat(minLon + 1, 1), new LonLat(minLon, 1), new LonLat(minLon, 0)), List.of(), GeometryTolerance.DEFAULT);
        return new Zone(new ZoneKey(ZONE), version, ZoneType.CITY, 0, shape);
    }
}
