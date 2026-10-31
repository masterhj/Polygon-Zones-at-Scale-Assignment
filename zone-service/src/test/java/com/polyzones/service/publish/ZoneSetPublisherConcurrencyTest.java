package com.polyzones.service.publish;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ZoneSetPublisherConcurrencyTest {
    private static final UUID TENANT_A = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID TENANT_B = UUID.fromString("30000000-0000-0000-0000-000000000002");

    @Test
    @DisplayName("D-13: concurrent edits for one tenant receive unique ordered versions")
    void serializesConcurrentSameTenantEdits() throws Exception {
        Map<UUID, AtomicLong> versions = new ConcurrentHashMap<>();
        ZoneSetStore store = store(versions);
        SnapshotRegistry registry = new SnapshotRegistry();
        SnapshotLoader loader = loader(null);
        try (ZoneSetPublisher publisher = publisher(store, loader, registry)) {
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<ZoneSetPublisher.PublishResult>> futures = new ArrayList<>();
            for (int worker = 0; worker < 2; worker++) futures.add(pool.submit(() -> {
                start.await();
                ZoneSetPublisher.PublishResult last = null;
                for (int i = 0; i < 20; i++) last = publisher.publish(TENANT_A, command(), Duration.ofSeconds(10));
                return last;
            }));
            start.countDown();
            for (Future<?> future : futures) future.get(20, TimeUnit.SECONDS);
            pool.shutdownNow();
            assertThat(versions.get(TENANT_A).get()).isEqualTo(40);
            assertThat(registry.latest(TENANT_A).setVersion()).isEqualTo(40);
        }
    }

    @Test
    @DisplayName("D-13: different tenants can build snapshots concurrently")
    void doesNotShareWriterLaneAcrossTenants() throws Exception {
        Map<UUID, AtomicLong> versions = new ConcurrentHashMap<>();
        CountDownLatch bothBuilding = new CountDownLatch(2);
        SnapshotLoader loader = loader(bothBuilding);
        SnapshotRegistry registry = new SnapshotRegistry();
        try (ZoneSetPublisher publisher = publisher(store(versions), loader, registry)) {
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<?> first = pool.submit(() -> { start.await(); publisher.publish(TENANT_A, command(), Duration.ofSeconds(10)); return null; });
            Future<?> second = pool.submit(() -> { start.await(); publisher.publish(TENANT_B, command(), Duration.ofSeconds(10)); return null; });
            start.countDown();
            assertThat(bothBuilding.await(5, TimeUnit.SECONDS)).isTrue();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
            pool.shutdownNow();
            assertThat(registry.latest(TENANT_A).setVersion()).isEqualTo(1);
            assertThat(registry.latest(TENANT_B).setVersion()).isEqualTo(1);
        }
    }

    private static ZoneSetPublisher publisher(ZoneSetStore store, SnapshotLoader loader, SnapshotRegistry registry) {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        return new ZoneSetPublisher(store, loader, registry, ignored -> List.of(), ignored -> false,
                meters, 2, 8, new ZoneVersionGaugeRegistry(meters, 10), ignored -> { });
    }
    private static ZoneSetStore store(Map<UUID, AtomicLong> versions) {
        return new ZoneSetStore() {
            @Override public long beginBuild(UUID tenant, ZoneEditCommand command) { return versions.computeIfAbsent(tenant, ignored -> new AtomicLong()).incrementAndGet(); }
            @Override public void markPublished(UUID tenant, long version) { }
            @Override public void markFailed(UUID tenant, long version) { }
            @Override public ZoneSetPublisher.PublishResult findIdempotent(UUID tenant, String key, String hash) { return null; }
        };
    }
    private static SnapshotLoader loader(CountDownLatch barrier) {
        return new SnapshotLoader() {
            @Override public ZoneSnapshot loadPublished(UUID tenant, long version) { return null; }
            @Override public ZoneSnapshot loadCandidate(UUID tenant, long version) {
                if (barrier != null) {
                    barrier.countDown();
                    try { if (!barrier.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("other tenant did not build concurrently"); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
                }
                return snapshot(tenant, version);
            }
        };
    }
    private static ZoneEditCommand command() { return new ZoneEditCommand(List.of(), List.of(), null, null); }
    private static ZoneSnapshot snapshot(UUID tenant, long version) {
        PolygonShape shape = PolygonShape.of(List.of(new LonLat(0, 0), new LonLat(1, 0), new LonLat(1, 1),
                new LonLat(0, 1), new LonLat(0, 0)), List.of(), GeometryTolerance.DEFAULT);
        Zone zone = new Zone(new ZoneKey(UUID.nameUUIDFromBytes((tenant + ":" + version).getBytes())),
                (int) version, ZoneType.CITY, 0, shape);
        return SnapshotBuilder.build(tenant, version, List.of(zone));
    }
}
