package com.polyzones.service.publish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.polyzones.core.geo.GeometryTolerance;
import com.polyzones.core.geo.LonLat;
import com.polyzones.core.index.SnapshotBuilder;
import com.polyzones.core.index.ZoneSnapshot;
import com.polyzones.core.shape.PolygonShape;
import com.polyzones.core.zone.Zone;
import com.polyzones.core.zone.ZoneKey;
import com.polyzones.core.zone.ZoneType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.polyzones.service.observability.ZoneVersionGaugeRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ZoneSetPublisherTest {
    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    @DisplayName("D-13: validation precedes allocation; build precedes publish commit and atomic swap")
    void successUsesDatabaseFirstOrder() {
        List<String> order = new ArrayList<>();
        FakeStore store = new FakeStore(order);
        SnapshotRegistry registry = new SnapshotRegistry();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        try (ZoneSetPublisher publisher = publisher(store, registry, order, false, meters)) {
            ZoneSetPublisher.PublishResult result = publisher.publish(TENANT, command(), Duration.ofSeconds(5));
            assertThat(result.setVersion()).isEqualTo(1);
            assertThat(registry.latest(TENANT).setVersion()).isEqualTo(1);
            assertThat(order).containsExactly("validate", "begin", "candidate", "published");
            assertThat(meters.counter("index_swap_total").count()).isEqualTo(1);
            assertThat(meters.find("zone_set_version").tag("tenant", TENANT.toString()).gauge().value()).isEqualTo(1.0);
        }
    }

    @Test
    @DisplayName("D-13: build failure marks only the allocated candidate failed and keeps old snapshot")
    void failedBuildDoesNotSwapSnapshot() {
        List<String> order = new ArrayList<>();
        FakeStore store = new FakeStore(order);
        SnapshotRegistry registry = new SnapshotRegistry();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        registry.publish(TENANT, snapshot(0));
        try (ZoneSetPublisher publisher = publisher(store, registry, order, true, meters)) {
            assertThatThrownBy(() -> publisher.publish(TENANT, command(), Duration.ofSeconds(5)))
                    .isInstanceOf(ZoneSetPublisher.PublishFailedException.class);
            assertThat(registry.latest(TENANT).setVersion()).isZero();
            assertThat(order).containsExactly("validate", "begin", "failed");
            assertThat(meters.counter("index_swap_failures_total").count()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("D-13: a post-commit swap failure leaves PUBLISHED durable and fails the service closed")
    void postCommitFailureIsFatalAndNeverRewritesPublishedStatus() {
        List<String> order = new ArrayList<>();
        FakeStore store = new FakeStore(order);
        SnapshotRegistry registry = new SnapshotRegistry();
        registry.publish(TENANT, snapshot(1));
        AtomicReference<Throwable> fatal = new AtomicReference<>();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        SnapshotLoader loader = new SnapshotLoader() {
            @Override public ZoneSnapshot loadPublished(UUID tenant, long version) { return null; }
            @Override public ZoneSnapshot loadCandidate(UUID tenant, long version) { order.add("candidate"); return snapshot(version); }
        };
        try (ZoneSetPublisher publisher = new ZoneSetPublisher(store, loader, registry, cmd -> {
            order.add("validate"); return List.of();
        }, ignored -> false, meters, 1, 2, new ZoneVersionGaugeRegistry(meters, 10), fatal::set)) {
            assertThatThrownBy(() -> publisher.publish(TENANT, command(), Duration.ofSeconds(5)))
                    .isInstanceOf(ZoneSetPublisher.PublishFailedException.class);
            assertThat(fatal.get()).isNotNull();
            assertThat(order).containsExactly("validate", "begin", "candidate", "published");
            assertThat(registry.latest(TENANT).setVersion()).isEqualTo(1);
        }
    }

    private static ZoneSetPublisher publisher(FakeStore store, SnapshotRegistry registry, List<String> order,
                                              boolean failBuild, SimpleMeterRegistry meters) {
        SnapshotLoader loader = new SnapshotLoader() {
            @Override public ZoneSnapshot loadPublished(UUID tenant, long version) { return null; }
            @Override public ZoneSnapshot loadCandidate(UUID tenant, long version) { order.add("candidate"); return snapshot(version); }
        };
        return new ZoneSetPublisher(store, loader, registry, cmd -> { order.add("validate"); return List.of(); },
                cmd -> failBuild, meters, 1, 2, new ZoneVersionGaugeRegistry(meters, 10), ignored -> { });
    }

    private static ZoneEditCommand command() { return new ZoneEditCommand(List.of(zone(1)), List.of(), null, null); }
    private static ZoneSnapshot snapshot(long setVersion) { return SnapshotBuilder.build(TENANT, setVersion, List.of(zone((int) Math.max(1, setVersion)))); }
    private static Zone zone(int version) {
        PolygonShape shape = PolygonShape.of(List.of(new LonLat(0, 0), new LonLat(1, 0), new LonLat(1, 1),
                new LonLat(0, 1), new LonLat(0, 0)), List.of(), GeometryTolerance.DEFAULT);
        return new Zone(new ZoneKey(UUID.nameUUIDFromBytes(("z" + version).getBytes())), version, ZoneType.CITY, 0, shape);
    }

    private static final class FakeStore implements ZoneSetStore {
        private final List<String> order;
        private final AtomicLong versions = new AtomicLong();
        FakeStore(List<String> order) { this.order = order; }
        @Override public long beginBuild(UUID tenant, ZoneEditCommand command) { order.add("begin"); return versions.incrementAndGet(); }
        @Override public void markPublished(UUID tenant, long version) { order.add("published"); }
        @Override public void markFailed(UUID tenant, long version) { order.add("failed"); }
        @Override public ZoneSetPublisher.PublishResult findIdempotent(UUID tenant, String key, String hash) { return null; }
    }
}
