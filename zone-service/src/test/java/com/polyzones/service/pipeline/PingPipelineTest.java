package com.polyzones.service.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.polyzones.core.geo.LonLat;
import com.polyzones.core.index.SnapshotBuilder;
import com.polyzones.core.index.ZoneSnapshot;
import com.polyzones.core.trip.NightWindow;
import com.polyzones.core.trip.Ping;
import com.polyzones.core.trip.TripAttributor;
import com.polyzones.core.trip.TripState;
import com.polyzones.core.trip.ZoneEvent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PingPipelineTest {
    @Test void duplicateAndShuffledPingsResolveInOrderAndCheckpointCompleteState() {
        Fixture ordered = fixture(1, 2, ignored -> { });
        ordered.processor.accept(ping(0, 0));
        ordered.processor.accept(ping(1, .1));
        assertThat(ordered.processor.durableAckedSeq()).isEqualTo(1);
        assertThat(ordered.processor.accept(ping(1, .1)).status()).isEqualTo(TripProcessor.Status.DUPLICATE);
        assertThat(ordered.processor.duplicatePings()).isEqualTo(1);

        Fixture shuffled = fixture(2, 2, ignored -> { });
        assertThat(shuffled.processor.accept(ping(1, .1)).status()).isEqualTo(TripProcessor.Status.BUFFERED);
        var released = shuffled.processor.accept(ping(0, 0));
        assertThat(released.applied()).extracting(result -> result.processedSeq()).containsExactly(0L, 1L);
        assertThat(shuffled.processor.durableAckedSeq()).isEqualTo(1);
        assertThat(shuffled.state.distanceMeters()).isEqualTo(ordered.state.distanceMeters());
    }

    @Test void expiredGapIsPersistedAndLateVersusDuplicateClassificationSurvivesRestart() {
        MutableClock clock = new MutableClock(Instant.EPOCH);
        Fixture fixture = fixture(clock, 100, 64, ignored -> { });
        assertThat(fixture.processor.accept(ping(3, .3)).status()).isEqualTo(TripProcessor.Status.BUFFERED);
        clock.advance(Duration.ofMillis(2001));
        assertThat(fixture.processor.tick().applied()).extracting(result -> result.processedSeq()).containsExactly(3L);
        assertThat(fixture.state.skippedRanges()).containsExactly(new com.polyzones.core.trip.SkippedRange(0, 2));
        assertThat(fixture.processor.gapsSkipped()).isEqualTo(1);
        assertThat(fixture.processor.accept(ping(2, .2)).status()).isEqualTo(TripProcessor.Status.LATE);
        assertThat(fixture.processor.accept(ping(3, .3)).status()).isEqualTo(TripProcessor.Status.DUPLICATE);

        var recovered = new ReorderBuffer(fixture.state.processedSeq() + 1, 64, Duration.ofSeconds(2), clock,
                fixture.state.skippedRanges());
        assertThat(recovered.offer(ping(2, .2)).disposition()).isEqualTo(ReorderBuffer.Disposition.LATE);
        assertThat(recovered.offer(ping(3, .3)).disposition()).isEqualTo(ReorderBuffer.Disposition.DUPLICATE);
    }

    private static Fixture fixture(int checkpointPings, int maxBuffered, java.util.function.Consumer<CheckpointSnapshot> persist) {
        return fixture(new MutableClock(Instant.EPOCH), checkpointPings, maxBuffered, persist);
    }
    private static Fixture fixture(MutableClock clock, int checkpointPings, int maxBuffered,
                                   java.util.function.Consumer<CheckpointSnapshot> persist) {
        UUID tenant = new UUID(0, 1), trip = new UUID(0, 2);
        ZoneSnapshot snapshot = SnapshotBuilder.build(tenant, 1, List.of());
        TripAttributor attributor = new TripAttributor(tenant, trip, snapshot);
        TripState state = attributor.state();
        TripCheckpointWriter writer = new TripCheckpointWriter(persist::accept);
        ReorderBuffer reorder = new ReorderBuffer(state.processedSeq() + 1, maxBuffered, Duration.ofSeconds(2), clock, List.of());
        TripProcessor processor = new TripProcessor(attributor, state, reorder, () -> snapshot,
                new NightWindow(java.time.LocalTime.of(22, 0), java.time.LocalTime.of(6, 0)), ZoneId.of("UTC"),
                clock, writer, checkpointPings, Duration.ofDays(1));
        return new Fixture(processor, state);
    }
    private static Ping ping(long seq, double lon) {
        return new Ping(new UUID(0, 2), seq, Instant.EPOCH.plusSeconds(seq), new LonLat(lon, 0), null);
    }
    private record Fixture(TripProcessor processor, TripState state) { }
    static final class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant now) { this.now = now; }
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
