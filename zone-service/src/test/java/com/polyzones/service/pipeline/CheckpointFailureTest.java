package com.polyzones.service.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.polyzones.core.geo.LonLat;
import com.polyzones.core.index.SnapshotBuilder;
import com.polyzones.core.index.ZoneSnapshot;
import com.polyzones.core.trip.NightWindow;
import com.polyzones.core.trip.Ping;
import com.polyzones.core.trip.TripAttributor;
import com.polyzones.core.trip.TripState;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CheckpointFailureTest {
    @Test void transientFailuresRetainSameFrontierAndAckOnlyAfterCommit() {
        UUID tenant = new UUID(0, 1), trip = new UUID(0, 2);
        ZoneSnapshot snapshot = SnapshotBuilder.build(tenant, 1, List.of());
        TripAttributor attributor = new TripAttributor(tenant, trip, snapshot);
        TripState state = attributor.state();
        AtomicInteger attempts = new AtomicInteger();
        List<CheckpointSnapshot> seen = new ArrayList<>(), committed = new ArrayList<>();
        TripCheckpointWriter writer = new TripCheckpointWriter(value -> {
            seen.add(value);
            if (attempts.incrementAndGet() <= 3) throw new IllegalStateException("injected database outage");
            committed.add(value);
        });
        Clock clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);
        TripProcessor processor = new TripProcessor(attributor, state,
                new ReorderBuffer(0, 64, Duration.ofSeconds(2), clock, List.of()), () -> snapshot,
                new NightWindow(java.time.LocalTime.of(22, 0), java.time.LocalTime.of(6, 0)), ZoneId.of("UTC"),
                clock, writer, 1, Duration.ofDays(1));

        processor.accept(new Ping(trip, 0, Instant.EPOCH, new LonLat(0, 0), null));
        assertThat(processor.durableAckedSeq()).isEqualTo(-1);
        assertThat(processor.checkpointFailures()).isEqualTo(3);
        assertThat(processor.tick().status()).isEqualTo(TripProcessor.Status.BUFFERED);
        assertThat(processor.durableAckedSeq()).isZero();
        assertThat(seen).hasSize(4);
        assertThat(seen).allMatch(snapshotValue -> snapshotValue == seen.getFirst());
        assertThat(committed.getFirst().stateCheckpoint().distanceMeters()).isEqualTo(state.distanceMeters());
    }
}
