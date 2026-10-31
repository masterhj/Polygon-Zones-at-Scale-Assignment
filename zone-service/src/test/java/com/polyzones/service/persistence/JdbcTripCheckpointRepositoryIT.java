package com.polyzones.service.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.polyzones.core.geo.LonLat;
import com.polyzones.core.index.ZoneSnapshot;
import com.polyzones.core.trip.NightWindow;
import com.polyzones.core.trip.Ping;
import com.polyzones.core.trip.TripAttributor;
import com.polyzones.core.trip.TripState;
import com.polyzones.core.shape.PolygonShape;
import com.polyzones.core.zone.Zone;
import com.polyzones.core.zone.ZoneKey;
import com.polyzones.core.zone.ZoneType;
import com.polyzones.service.pipeline.ReorderBuffer;
import com.polyzones.service.pipeline.TripCheckpointWriter;
import com.polyzones.service.pipeline.TripProcessor;
import com.polyzones.service.publish.PinnedSnapshotRegistry;
import com.polyzones.service.publish.ZoneEditCommand;
import com.polyzones.service.publish.ZoneSetPublisher;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
@SpringBootTest(classes = com.polyzones.service.ZoneServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class JdbcTripCheckpointRepositoryIT {
    @Container @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18");
    private static final UUID TENANT = new UUID(0, 101), EMPLOYEE = new UUID(0, 102), TRIP = new UUID(0, 103);
    @Autowired JdbcTemplate jdbc;
    @Autowired ZoneSetPublisher publisher;
    @Autowired JdbcTripCheckpointRepository repository;
    @Autowired PinnedSnapshotRegistry pinned;

    @Test void commitsCheckpointAndDurableFrontierInOneTransaction() {
        jdbc.update("insert into tenants(id,name) values (?,?) on conflict do nothing", TENANT, "checkpoint-it-" + TENANT);
        jdbc.update("insert into app_users(id,tenant_id,username,password_hash,role) values (?,?,?,?,?) on conflict do nothing",
                EMPLOYEE, TENANT, "employee", "unused", "EMPLOYEE");
        Zone city = zone(201, ZoneType.CITY);
        Zone noGo = zone(202, ZoneType.NO_GO);
        long setVersion = publisher.publish(TENANT, new ZoneEditCommand(List.of(city, noGo), List.of(), null, null), Duration.ofSeconds(10)).setVersion();
        ZoneSnapshot snapshot = pinned.acquire(TENANT, setVersion);
        try {
            jdbc.update("insert into trips(tenant_id,trip_id,employee_id,pinned_set_version,status,started_at) values (?,?,?,?,'IN_PROGRESS',?)",
                    TENANT, TRIP, EMPLOYEE, setVersion, java.sql.Timestamp.from(Instant.EPOCH));
            TripAttributor attributor = new TripAttributor(TENANT, TRIP, snapshot);
            TripState state = attributor.state();
            Clock clock = Clock.fixed(Instant.parse("2026-10-31T20:00:00Z"), ZoneId.of("UTC"));
            TripProcessor processor = new TripProcessor(attributor, state,
                    new ReorderBuffer(0, 64, Duration.ofSeconds(2), clock, List.of()), () -> snapshot,
                    new NightWindow(LocalTime.of(22, 0), LocalTime.of(6, 0)), ZoneId.of("Asia/Kolkata"), clock,
                    new TripCheckpointWriter(repository), 2, Duration.ofSeconds(2));
            processor.accept(new Ping(TRIP, 0, Instant.parse("2026-10-31T17:00:00Z"), new LonLat(-.1, .05), null));
            assertThat(processor.durableAckedSeq()).isEqualTo(-1);
            processor.accept(new Ping(TRIP, 1, Instant.parse("2026-10-31T17:10:00Z"), new LonLat(.05, .05), null));
            assertThat(processor.durableAckedSeq()).isEqualTo(1);
            assertThat(jdbc.queryForObject("select durable_acked_seq from trips where tenant_id=? and trip_id=?", Long.class, TENANT, TRIP)).isEqualTo(1L);
            String checkpoint = jdbc.queryForObject("select checkpoint::text from trips where tenant_id=? and trip_id=?", String.class, TENANT, TRIP);
            var restored = new CheckpointStateCodec(JsonMapper.builder().build()).decode(checkpoint);
            assertThat(TripState.restore(restored).lastPoint()).isEqualTo(new LonLat(.05, .05));
            assertThat(restored.durableAckedSeq()).isEqualTo(1);
            assertThat(repository.loadState(TENANT, TRIP)).isEqualTo(restored);
            assertThat(jdbc.queryForObject("select count(*) from trip_zone_distance where tenant_id=? and trip_id=?", Integer.class, TENANT, TRIP)).isEqualTo(1);
            double zoneMeters = jdbc.queryForObject("select meters from trip_zone_distance where tenant_id=? and trip_id=?", Double.class, TENANT, TRIP);
            double unzonedMeters = jdbc.queryForObject("select unzoned_meters from trips where tenant_id=? and trip_id=?", Double.class, TENANT, TRIP);
            assertThat(zoneMeters).isGreaterThan(0);
            assertThat(zoneMeters + unzonedMeters).isCloseTo(restored.totalMeters(), org.assertj.core.data.Offset.offset(1e-6));
            assertThat(jdbc.queryForObject("select count(*) from zone_events where tenant_id=? and trip_id=?", Integer.class, TENANT, TRIP)).isEqualTo(2);
            assertThat(jdbc.queryForObject("select count(*) from alerts where tenant_id=? and trip_id=?", Integer.class, TENANT, TRIP)).isEqualTo(1);
        } finally {
            pinned.release(TENANT, setVersion);
        }
    }

    private static Zone zone(long id, ZoneType type) {
        PolygonShape shape = PolygonShape.of(List.of(new LonLat(0, 0), new LonLat(.1, 0), new LonLat(.1, .1),
                new LonLat(0, .1), new LonLat(0, 0)), List.of(), com.polyzones.core.geo.GeometryTolerance.DEFAULT);
        return new Zone(new ZoneKey(new UUID(0, id)), 1, type, 0, shape);
    }
}
