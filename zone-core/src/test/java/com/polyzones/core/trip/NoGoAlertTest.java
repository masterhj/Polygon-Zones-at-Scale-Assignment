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
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NoGoAlertTest {
    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TRIP = UUID.fromString("10000000-0000-0000-0000-000000000009");
    private static final ZoneKey NO_GO = new ZoneKey(UUID.fromString("20000000-0000-0000-0000-000000000009"));
    private static final NightWindow NIGHT = new NightWindow(LocalTime.of(22, 0), LocalTime.of(6, 0));
    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    @Test
    @DisplayName("R3.3 / D-07: night entry and trip-start-inside create deterministic no-go alerts")
    void nightEntryAlertsAndReplayIsIdempotent() {
        ZoneSnapshot pinned = SnapshotBuilder.build(TENANT, 1, List.of());
        ZoneSnapshot latest = snapshot(1, noGo(1, 0, .1));
        TripAttributor first = new TripAttributor(TENANT, TRIP, pinned);
        ProcessResult result = first.apply(ping(0, "2026-10-31T17:30:00Z", .05, .05), latest, NIGHT, INDIA); // 23:00 IST
        assertThat(result.alerts()).hasSize(1);
        assertThat(result.events()).singleElement().satisfies(event -> {
            assertThat(event.kind()).isEqualTo(TransitionKind.ENTER);
            assertThat(event.reason()).isEqualTo(EventReason.TRIP_START);
            assertThat(event.evaluatedSetVersion()).isEqualTo(1);
        });
        TripAttributor replay = new TripAttributor(TENANT, TRIP, pinned);
        ProcessResult replayed = replay.apply(ping(0, "2026-10-31T17:30:00Z", .05, .05), latest, NIGHT, INDIA);
        assertThat(replayed.events().getFirst().eventId()).isEqualTo(result.events().getFirst().eventId());
        assertThat(replayed.alerts().getFirst().alertId()).isEqualTo(result.alerts().getFirst().alertId());
    }

    @Test
    @DisplayName("R3.3 / D-07: day entry creates no alert and night-window bounds are exact")
    void dayAndBoundaryTimes() {
        ZoneSnapshot empty = SnapshotBuilder.build(TENANT, 1, List.of());
        ZoneSnapshot latest = snapshot(1, noGo(1, 0, .1));
        TripAttributor day = new TripAttributor(TENANT, TRIP, empty);
        assertThat(day.apply(ping(0, "2026-10-31T08:30:00Z", .05, .05), latest, NIGHT, INDIA).alerts()).isEmpty(); // 14:00 IST
        TripAttributor before = new TripAttributor(TENANT, TRIP, empty);
        assertThat(before.apply(ping(0, "2026-10-31T16:29:59Z", .05, .05), latest, NIGHT, INDIA).alerts()).isEmpty();
        TripAttributor atStart = new TripAttributor(TENANT, TRIP, empty);
        assertThat(atStart.apply(ping(0, "2026-10-31T16:30:00Z", .05, .05), latest, NIGHT, INDIA).alerts()).hasSize(1);
        TripAttributor atEnd = new TripAttributor(TENANT, TRIP, empty);
        assertThat(atEnd.apply(ping(0, "2026-10-31T00:30:00Z", .05, .05), latest, NIGHT, INDIA).alerts()).isEmpty(); // 06:00 IST
    }

    @Test
    @DisplayName("R3.3 / D-07: no-go entry during a DST transition uses tenant wall-clock time")
    void daylightSavingTransitionIsTenantLocal() {
        ZoneSnapshot empty = SnapshotBuilder.build(TENANT, 1, List.of());
        ZoneSnapshot latest = snapshot(1, noGo(1, 0, .1));
        TripAttributor attributor = new TripAttributor(TENANT, TRIP, empty);
        ProcessResult result = attributor.apply(ping(0, "2026-03-08T07:30:00Z", .05, .05), latest, NIGHT,
                ZoneId.of("America/New_York")); // 03:30 after the spring-forward gap
        assertThat(result.alerts()).hasSize(1);
    }

    @Test
    @DisplayName("D-09: a live no-go shrink stops safety events on the very next ping")
    void shrinkIsEffectiveForNextPingWhileBillingRemainsPinned() {
        ZoneSnapshot pinned = snapshot(1, city(1));
        ZoneSnapshot safety1 = snapshot(1, noGo(1, 0, .1));
        ZoneSnapshot safety2 = snapshot(2, noGo(2, .04, .06));
        TripAttributor attributor = new TripAttributor(TENANT, TRIP, pinned);
        attributor.apply(ping(0, "2026-10-31T12:00:00Z", .02, .05), safety1, NIGHT, INDIA);
        ProcessResult moved = attributor.apply(ping(1, "2026-10-31T12:01:00Z", .03, .05), safety2, NIGHT, INDIA);
        assertThat(moved.events()).noneMatch(event -> event.kind() == TransitionKind.ENTER && event.zoneId().equals(NO_GO));
        assertThat(moved.events()).anySatisfy(event -> {
            assertThat(event.zoneId()).isEqualTo(NO_GO);
            assertThat(event.kind()).isEqualTo(TransitionKind.EXIT);
            assertThat(event.reason()).isEqualTo(EventReason.ZONE_CHANGED);
            assertThat(event.evaluatedSetVersion()).isEqualTo(2);
        });
        assertThat(attributor.state().pinnedSetVersion()).isEqualTo(1);
    }

    @Test
    @DisplayName("D-21: two qualifying entries produce distinct deterministic alert IDs")
    void separateEntriesHaveSeparateAlerts() {
        ZoneSnapshot empty = SnapshotBuilder.build(TENANT, 1, List.of());
        ZoneSnapshot latest = snapshot(1, noGo(1, 0, .1));
        TripAttributor attributor = new TripAttributor(TENANT, TRIP, empty);
        attributor.apply(ping(0, "2026-10-31T17:00:00Z", -.1, .05), latest, NIGHT, INDIA);
        ProcessResult result = attributor.apply(ping(1, "2026-10-31T17:10:00Z", .05, .05), latest, NIGHT, INDIA);
        ProcessResult exit = attributor.apply(ping(2, "2026-10-31T17:20:00Z", .2, .05), latest, NIGHT, INDIA);
        ProcessResult enterAgain = attributor.apply(ping(3, "2026-10-31T17:30:00Z", .05, .05), latest, NIGHT, INDIA);
        assertThat(result.alerts()).hasSize(1);
        assertThat(exit.alerts()).isEmpty();
        assertThat(enterAgain.alerts()).hasSize(1);
        assertThat(enterAgain.alerts().getFirst().alertId()).isNotEqualTo(result.alerts().getFirst().alertId());
    }

    private static Ping ping(long seq, String time, double lon, double lat) { return new Ping(TRIP, seq, Instant.parse(time), new LonLat(lon, lat), null); }
    private static ZoneSnapshot snapshot(long version, Zone... zones) { return SnapshotBuilder.build(TENANT, version, List.of(zones)); }
    private static Zone noGo(int version, double minLon, double maxLon) { return box(NO_GO, version, minLon, maxLon, ZoneType.NO_GO, 0); }
    private static Zone city(int version) { return box(new ZoneKey(UUID.fromString("20000000-0000-0000-0000-000000000001")), version, 0, .1, ZoneType.CITY, 0); }
    private static Zone box(ZoneKey key, int version, double minLon, double maxLon, ZoneType type, int priority) {
        List<LonLat> points = List.of(new LonLat(minLon, 0), new LonLat(maxLon, 0), new LonLat(maxLon, .1),
                new LonLat(minLon, .1), new LonLat(minLon, 0));
        return new Zone(key, version, type, priority, PolygonShape.of(points, List.of(), GeometryTolerance.DEFAULT));
    }
}
