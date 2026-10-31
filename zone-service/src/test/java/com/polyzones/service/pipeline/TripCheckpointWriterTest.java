package com.polyzones.service.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.polyzones.core.geo.LonLat;
import com.polyzones.core.index.SnapshotBuilder;
import com.polyzones.core.index.ZoneSnapshot;
import com.polyzones.core.trip.NightWindow;
import com.polyzones.core.trip.Ping;
import com.polyzones.core.trip.TripAttributor;
import com.polyzones.core.trip.TripState;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TripCheckpointWriterTest {
    @Test void retriesIdenticalImmutableCheckpointAndAdvancesOnlyAfterCommit() {
        UUID tenant = new UUID(0, 1), trip = new UUID(0, 2);
        TripState state = processedState(tenant, trip, 5).attributor().state();
        AtomicInteger attempts = new AtomicInteger();
        List<CheckpointSnapshot> captured = new ArrayList<>();
        TripCheckpointWriter writer = new TripCheckpointWriter(snapshot -> {
            captured.add(snapshot);
            if (attempts.incrementAndGet() <= 2) throw new IllegalStateException("injected");
        });
        assertThat(writer.write(state, List.of(), List.of(), List.of(new Ping(trip, 5, Instant.EPOCH, new LonLat(0, 0), null)))).isTrue();
        assertThat(state.durableAckedSeq()).isEqualTo(5);
        assertThat(writer.failures()).isEqualTo(2);
        assertThat(captured).hasSize(3);
        assertThat(captured.get(0)).isSameAs(captured.get(1)).isSameAs(captured.get(2));
        assertThat(captured.getFirst().durableAckedSeq()).isEqualTo(5);
    }

    @Test void failuresLeaveFrontierUnchangedAndRetainSameSnapshot() {
        TripState state = processedState(new UUID(0, 1), new UUID(0, 2), 10).attributor().state();
        List<CheckpointSnapshot> captured = new ArrayList<>();
        TripCheckpointWriter writer = new TripCheckpointWriter(snapshot -> {
            captured.add(snapshot);
            throw new IllegalStateException("offline");
        });
        assertThat(writer.write(state, List.of(), List.of(), List.of())).isFalse();
        assertThat(state.durableAckedSeq()).isEqualTo(-1);
        CheckpointSnapshot first = captured.getFirst();
        assertThat(writer.retryPending(state)).isFalse();
        assertThat(captured).allMatch(snapshot -> snapshot == first);
        assertThat(writer.hasPending()).isTrue();
        assertThat(writer.failures()).isEqualTo(6);
    }

    @Test void willNotReplaceAnUncommittedCapturedFrontier() {
        UUID tenant = new UUID(0, 1), trip = new UUID(0, 2);
        Processed processed = processedState(tenant, trip, 3);
        TripState state = processed.attributor().state();
        TripCheckpointWriter writer = new TripCheckpointWriter(snapshot -> { throw new IllegalStateException("offline"); });
        assertThat(writer.write(state, List.of(), List.of(), List.of())).isFalse();
        processed.attributor().apply(new Ping(trip, 4, Instant.EPOCH.plusSeconds(4), new LonLat(0, 0), null),
                processed.snapshot(), new NightWindow(java.time.LocalTime.of(22, 0), java.time.LocalTime.of(6, 0)), java.time.ZoneId.of("UTC"));
        assertThat(writer.write(state, List.of(), List.of(), List.of())).isFalse();
        assertThat(writer.committedFrontier()).isEqualTo(-1);
    }

    private static Processed processedState(UUID tenant, UUID trip, long seq) {
        ZoneSnapshot snapshot = SnapshotBuilder.build(tenant, 7, List.of());
        TripAttributor attributor = new TripAttributor(tenant, trip, snapshot);
        attributor.apply(new Ping(trip, seq, Instant.EPOCH, new LonLat(0, 0), null), snapshot,
                new NightWindow(java.time.LocalTime.of(22, 0), java.time.LocalTime.of(6, 0)), java.time.ZoneId.of("UTC"));
        return new Processed(attributor, snapshot);
    }
    private record Processed(TripAttributor attributor, ZoneSnapshot snapshot) { }
}
