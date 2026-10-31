package com.polyzones.service.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.polyzones.core.geo.LonLat;
import com.polyzones.core.geo.GeometryTolerance;
import com.polyzones.core.index.SnapshotBuilder;
import com.polyzones.core.index.ZoneSnapshot;
import com.polyzones.core.trip.NightWindow;
import com.polyzones.core.trip.Ping;
import com.polyzones.core.trip.TripAttributor;
import com.polyzones.core.trip.TripState;
import com.polyzones.core.trip.ProcessResult;
import com.polyzones.core.trip.ZoneEvent;
import com.polyzones.core.zone.Zone;
import com.polyzones.core.zone.ZoneKey;
import com.polyzones.core.zone.ZoneType;
import com.polyzones.core.shape.PolygonShape;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TripRecoveryFailureTest {
    @Test void replayFromDurableFrontierMatchesUninterruptedAttribution() {
        UUID tenant = new UUID(0, 1), trip = new UUID(0, 2);
        ZoneSnapshot snapshot = SnapshotBuilder.build(tenant, 1, List.of(
                zone(11, ZoneType.CITY), zone(12, ZoneType.NO_GO)));
        List<CheckpointSnapshot> durable = new ArrayList<>();
        TripAttributor originalAttributor = new TripAttributor(tenant, trip, snapshot);
        TripState originalState = originalAttributor.state();
        TripProcessor original = processor(originalAttributor, originalState, snapshot,
                new TripCheckpointWriter(durable::add), 100);
        original.accept(ping(0, -.1));
        assertThat(original.checkpoint()).isTrue();
        CheckpointSnapshot committed = durable.getFirst();
        original.accept(ping(1, .05)); // volatile state after the last committed checkpoint
        TripAttributor uninterrupted = new TripAttributor(tenant, trip, snapshot);
        uninterrupted.apply(ping(0, -.1), snapshot, night(), ZoneId.of("Asia/Kolkata"));
        ProcessResult expected = uninterrupted.apply(ping(1, .05), snapshot, night(), ZoneId.of("Asia/Kolkata"));
        TripState recoveredState = TripState.restore(committed.stateCheckpoint());
        TripAttributor recoveredAttributor = new TripAttributor(snapshot, recoveredState);
        List<CheckpointSnapshot> afterRecovery = new ArrayList<>();
        TripProcessor recovered = processor(recoveredAttributor, recoveredState, snapshot,
                new TripCheckpointWriter(afterRecovery::add), 2);
        recovered.accept(ping(1, .05)); // producer replay from durableAckedSeq + 1
        assertThat(recovered.checkpoint()).isTrue();
        assertThat(recoveredState.distanceMeters()).isEqualTo(originalState.distanceMeters());
        assertThat(afterRecovery.getFirst().events()).isEqualTo(expected.events());
        assertThat(afterRecovery.getFirst().alerts()).isEqualTo(expected.alerts());
        assertThat(afterRecovery.getFirst().stateCheckpoint().distanceMeters()).isEqualTo(uninterrupted.state().distanceMeters());
        assertThat(afterRecovery.getFirst().stateCheckpoint().nextTransitionOrdinal())
                .isEqualTo(originalState.nextTransitionOrdinal());
    }

    private static TripProcessor processor(TripAttributor attributor, TripState state, ZoneSnapshot snapshot,
                                           TripCheckpointWriter writer, int cadence) {
        Clock clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);
        return new TripProcessor(attributor, state, new ReorderBuffer(state.processedSeq() + 1, 64,
                Duration.ofSeconds(2), clock, state.skippedRanges()), () -> snapshot,
                night(), ZoneId.of("Asia/Kolkata"),
                clock, writer, cadence, Duration.ofDays(1));
    }
    private static Ping ping(long seq, double lon) {
        return new Ping(new UUID(0, 2), seq, Instant.parse(seq == 0 ? "2026-10-31T17:00:00Z" : "2026-10-31T17:10:00Z"), new LonLat(lon, .05), null);
    }
    private static NightWindow night() { return new NightWindow(java.time.LocalTime.of(22, 0), java.time.LocalTime.of(6, 0)); }
    private static Zone zone(long id, ZoneType type) {
        ZoneKey key = new ZoneKey(new UUID(0, id));
        PolygonShape shape = PolygonShape.of(List.of(new LonLat(0, 0), new LonLat(.1, 0), new LonLat(.1, .1),
                new LonLat(0, .1), new LonLat(0, 0)), List.of(), GeometryTolerance.DEFAULT);
        return new Zone(key, 1, type, 0, shape);
    }
}
