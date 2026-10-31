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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SnapshotSwapConcurrencyTest {
    private static final int ZONES = 16;
    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    @DisplayName("R4.1 / K3: atomic snapshot swap never exposes a torn or regressing version")
    void readersObserveWholeMonotonicSnapshots() throws Exception {
        SnapshotRegistry registry = new SnapshotRegistry();
        assertThat(registry.publish(TENANT, snapshot(1))).isTrue();
        int readers = 16;
        CountDownLatch ready = new CountDownLatch(readers + 1);
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean writerDone = new AtomicBoolean();
        ExecutorService pool = Executors.newFixedThreadPool(readers + 1);
        List<Future<?>> futures = new ArrayList<>();
        for (int r = 0; r < readers; r++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                long observed = 0;
                do {
                    ZoneSnapshot snapshot = registry.latest(TENANT);
                    assertThat(snapshot.setVersion()).isGreaterThanOrEqualTo(observed);
                    observed = snapshot.setVersion();
                    var hits = snapshot.index().query(0, 0);
                    assertThat(hits).hasSize(ZONES);
                    assertThat(hits).allSatisfy(hit -> assertThat(hit.version()).isEqualTo(snapshot.setVersion()));
                } while (!writerDone.get() || registry.latest(TENANT).setVersion() < 200);
                return null;
            }));
        }
        futures.add(pool.submit(() -> {
            ready.countDown();
            start.await();
            for (int version = 2; version <= 200; version++) {
                assertThat(registry.publish(TENANT, snapshot(version))).isTrue();
            }
            writerDone.set(true);
            return null;
        }));
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        try {
            for (Future<?> future : futures) future.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(registry.latest(TENANT).setVersion()).isEqualTo(200);
    }

    @Test
    @DisplayName("R4.1: slow snapshot construction leaves the old complete snapshot available")
    void readersKeepUsingOldSnapshotDuringBuild() {
        SnapshotRegistry registry = new SnapshotRegistry();
        ZoneSnapshot first = snapshot(1);
        assertThat(registry.publish(TENANT, first)).isTrue();
        CountDownLatch building = new CountDownLatch(1);
        CountDownLatch allowBuild = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ZoneSnapshot> candidate = executor.submit(() -> {
                building.countDown();
                allowBuild.await();
                return snapshot(2);
            });
            assertThat(building.await(5, TimeUnit.SECONDS)).isTrue();
            ZoneSnapshot captured = registry.latest(TENANT);
            assertThat(captured).isSameAs(first);
            assertThat(registry.latest(TENANT)).isSameAs(first);
            allowBuild.countDown();
            assertThat(registry.publish(TENANT, candidate.get(5, TimeUnit.SECONDS))).isTrue();
            assertThat(captured.setVersion()).isEqualTo(1);
            assertThat(registry.latest(TENANT).setVersion()).isEqualTo(2);
        } catch (Exception failure) {
            throw new AssertionError("Slow snapshot build failed", failure);
        } finally {
            allowBuild.countDown();
            executor.shutdownNow();
        }
    }

    private static ZoneSnapshot snapshot(int version) {
        List<Zone> zones = new ArrayList<>();
        for (int i = 0; i < ZONES; i++) {
            List<LonLat> ring = List.of(new LonLat(-1, -1), new LonLat(1, -1), new LonLat(1, 1),
                    new LonLat(-1, 1), new LonLat(-1, -1));
            zones.add(new Zone(new ZoneKey(UUID.nameUUIDFromBytes((version + ":" + i).getBytes())), version,
                    ZoneType.CITY, 0, PolygonShape.of(ring, List.of(), GeometryTolerance.DEFAULT)));
        }
        return SnapshotBuilder.build(TENANT, version, zones);
    }
}
