package com.polyzones.core.trip;

import static org.assertj.core.api.Assertions.assertThat;

import com.polyzones.core.geo.GeometryTolerance;
import com.polyzones.core.geo.LonLat;
import com.polyzones.core.index.SnapshotBuilder;
import com.polyzones.core.index.ZoneSnapshot;
import com.polyzones.core.shape.PolygonShape;
import com.polyzones.core.zone.Zone;
import com.polyzones.core.zone.ZoneKey;
import com.polyzones.core.zone.ZoneType;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TripAttributorEventsTest {
    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TRIP = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final ZoneKey CITY = new ZoneKey(UUID.fromString("20000000-0000-0000-0000-000000000001"));
    private static final NightWindow NIGHT = new NightWindow(java.time.LocalTime.of(22, 0), java.time.LocalTime.of(6, 0));

    @Test
    @DisplayName("R3.2 / D-21: crossing events use interpolated times and EXIT carries dwell")
    void crossingTimesAndDwellAreInterpolated() {
        ZoneSnapshot snapshot = snapshot(1, box(CITY, 0, 0, .1, .1, ZoneType.CITY, 0));
        TripAttributor attributor = new TripAttributor(TENANT, TRIP, snapshot);
        attributor.apply(ping(0, "2026-10-31T20:00:00Z", -.1, .05), snapshot, NIGHT, ZoneId.of("UTC"));
        ProcessResult result = attributor.apply(ping(1, "2026-10-31T20:02:00Z", .2, .05), snapshot, NIGHT, ZoneId.of("UTC"));

        assertThat(result.events()).extracting(ZoneEvent::kind).containsExactly(TransitionKind.ENTER, TransitionKind.EXIT);
        assertThat(result.events().getFirst().eventTime()).isEqualTo(Instant.parse("2026-10-31T20:00:40Z"));
        assertThat(result.events().getLast().eventTime()).isEqualTo(Instant.parse("2026-10-31T20:01:20Z"));
        assertThat(result.events().getLast().dwellSeconds()).isEqualTo(40.0);
        assertThat(result.events()).extracting(ZoneEvent::reason).containsOnly(EventReason.CROSSING);
    }

    @Test
    @DisplayName("R3.2 / D-07: trip-start membership emits TRIP_START and completion closes it")
    void tripStartsAndEndsInsideZone() {
        ZoneSnapshot snapshot = snapshot(4, box(CITY, 0, 0, .1, .1, ZoneType.CITY, 0));
        TripAttributor attributor = new TripAttributor(TENANT, TRIP, snapshot);
        ProcessResult first = attributor.apply(ping(0, "2026-10-31T20:00:00Z", .05, .05), snapshot, NIGHT, ZoneId.of("UTC"));
        assertThat(first.events()).singleElement().satisfies(event -> {
            assertThat(event.kind()).isEqualTo(TransitionKind.ENTER);
            assertThat(event.reason()).isEqualTo(EventReason.TRIP_START);
        });
        TripCompletion completion = attributor.complete(Instant.parse("2026-10-31T20:00:10Z"));
        assertThat(completion.exitEvents()).singleElement().satisfies(event -> {
            assertThat(event.kind()).isEqualTo(TransitionKind.EXIT);
            assertThat(event.reason()).isEqualTo(EventReason.TRIP_END);
            assertThat(event.dwellSeconds()).isEqualTo(10.0);
        });
        assertThat(attributor.state().completed()).isTrue();
    }

    @Test
    @DisplayName("D-10: an in-order event-time regression is clamped and counted")
    void timeRegressionCannotCreateNegativeDwell() {
        ZoneSnapshot snapshot = snapshot(1, box(CITY, 0, 0, .1, .1, ZoneType.CITY, 0));
        TripAttributor attributor = new TripAttributor(TENANT, TRIP, snapshot);
        attributor.apply(ping(1, "2026-10-31T20:00:00Z", .05, .05), snapshot, NIGHT, ZoneId.of("UTC"));
        ProcessResult result = attributor.apply(ping(2, "2026-10-31T19:00:00Z", .2, .05), snapshot, NIGHT, ZoneId.of("UTC"));
        assertThat(result.timeRegression()).isTrue();
        assertThat(attributor.state().timeRegressions()).isEqualTo(1);
        assertThat(result.events()).allSatisfy(event -> {
            assertThat(event.eventTime()).isEqualTo(Instant.parse("2026-10-31T20:00:00Z"));
            if (event.dwellSeconds() != null) assertThat(event.dwellSeconds()).isGreaterThanOrEqualTo(0);
        });
    }

    @Test
    @DisplayName("D-15: restoring a committed shard checkpoint preserves sequence, ordinal and distance state")
    void restoredCheckpointContinuesDeterministically() {
        ZoneSnapshot snapshot = snapshot(1, box(CITY, 0, 0, .1, .1, ZoneType.CITY, 0));
        TripAttributor uninterrupted = new TripAttributor(TENANT, TRIP, snapshot);
        Ping first = ping(0, "2026-10-31T20:00:00Z", -.1, .05);
        Ping second = ping(1, "2026-10-31T20:01:00Z", .05, .05);
        Ping third = ping(2, "2026-10-31T20:02:00Z", .2, .05);
        uninterrupted.apply(first, snapshot, NIGHT, ZoneId.of("UTC"));
        uninterrupted.apply(second, snapshot, NIGHT, ZoneId.of("UTC"));
        TripState committed = TripState.restore(uninterrupted.state().checkpoint());
        committed.acknowledgeDurable(1);

        TripAttributor resumed = new TripAttributor(snapshot, TripState.restore(committed.checkpoint()));
        ProcessResult expected = uninterrupted.apply(third, snapshot, NIGHT, ZoneId.of("UTC"));
        ProcessResult actual = resumed.apply(third, snapshot, NIGHT, ZoneId.of("UTC"));
        assertThat(actual).isEqualTo(expected);
        assertThat(resumed.complete(Instant.parse("2026-10-31T20:03:00Z")).summary())
                .isEqualTo(uninterrupted.complete(Instant.parse("2026-10-31T20:03:00Z")).summary());
    }

    @Test
    @DisplayName("R3.2 / D-02: boundary endpoint is inside and exits at the first interpolated instant")
    void boundaryEndpointStartsInsideThenExitsImmediately() {
        ZoneSnapshot snapshot = snapshot(1, box(CITY, 0, 0, .1, .1, ZoneType.CITY, 0));
        TripAttributor attributor = new TripAttributor(TENANT, TRIP, snapshot);
        Instant start = Instant.parse("2026-10-31T20:00:00Z");
        ProcessResult first = attributor.apply(new Ping(TRIP, 0, start, new LonLat(0, .05), null), snapshot, NIGHT, ZoneId.of("UTC"));
        assertThat(first.events()).singleElement().satisfies(event -> assertThat(event.reason()).isEqualTo(EventReason.TRIP_START));
        ProcessResult exit = attributor.apply(new Ping(TRIP, 1, start.plusSeconds(10), new LonLat(-.1, .05), null), snapshot, NIGHT, ZoneId.of("UTC"));
        assertThat(exit.events()).singleElement().satisfies(event -> {
            assertThat(event.kind()).isEqualTo(TransitionKind.EXIT);
            assertThat(event.eventTime()).isEqualTo(start);
            assertThat(event.dwellSeconds()).isZero();
        });
    }

    private static Ping ping(long seq, String time, double lon, double lat) {
        return new Ping(TRIP, seq, Instant.parse(time), new LonLat(lon, lat), null);
    }
    private static ZoneSnapshot snapshot(long setVersion, Zone... zones) { return SnapshotBuilder.build(TENANT, setVersion, List.of(zones)); }
    private static Zone box(ZoneKey id, double minX, double minY, double maxX, double maxY, ZoneType type, int priority) {
        PolygonShape shape = PolygonShape.of(List.of(new LonLat(minX, minY), new LonLat(maxX, minY), new LonLat(maxX, maxY),
                new LonLat(minX, maxY), new LonLat(minX, minY)), List.of(), GeometryTolerance.DEFAULT);
        return new Zone(id, 1, type, priority, shape);
    }
}
