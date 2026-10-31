package com.polyzones.service.publish;

import static org.assertj.core.api.Assertions.assertThat;

import com.polyzones.core.geo.BoundingBox;
import com.polyzones.core.index.SnapshotBuilder;
import com.polyzones.core.index.ZoneSnapshot;
import com.polyzones.core.index.synthetic.SyntheticPoints;
import com.polyzones.core.geo.GeometryTolerance;
import com.polyzones.core.geo.LonLat;
import com.polyzones.core.shape.PolygonShape;
import com.polyzones.core.zone.Zone;
import com.polyzones.core.zone.ZoneKey;
import com.polyzones.core.zone.ZoneType;
import com.polyzones.core.zone.Zone;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.IntConsumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("perf")
class NoLatencySpikeDuringRebuildTest {
    private static final long ZONE_SEED = 0x706861736534L;
    private static final long POINT_SEED = 0x72656275696c64L;
    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final BoundingBox EXTENT = new BoundingBox(77.4, 12.8, 77.8, 13.2);

    @Test
    @DisplayName("R4.1 / EX2: 5,000-zone queries continue during 500 ms background rebuilds")
    void rebuildDoesNotCauseUnboundedTailLatency() throws Exception {
        List<Zone> zones = syntheticZones();
        double[] points = SyntheticPoints.generate(POINT_SEED, 200_000, EXTENT, .1, zones);
        SnapshotRegistry registry = new SnapshotRegistry();
        registry.publish(TENANT, SnapshotBuilder.build(TENANT, 1, zones));
        Measured baseline = measure(registry, points, 0, 5);
        AtomicBoolean stop = new AtomicBoolean();
        AtomicLong rebuilds = new AtomicLong();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread rebuilder = new Thread(() -> {
            long version = 1;
            while (!stop.get()) {
                LockSupport.parkNanos(500_000_000L);
                if (stop.get()) break;
                try {
                    ZoneSnapshot next = SnapshotBuilder.build(TENANT, ++version, zones);
                    registry.publish(TENANT, next);
                    rebuilds.incrementAndGet();
                } catch (Throwable error) { failure.compareAndSet(null, error); return; }
            }
        }, "phase4-test-rebuilder");
        rebuilder.start();
        Measured during;
        try { during = measure(registry, points, 100_000, 5); }
        finally { stop.set(true); rebuilder.interrupt(); rebuilder.join(); }
        if (failure.get() != null) throw new AssertionError("Snapshot rebuild failed", failure.get());
        long baselineP99 = percentile(baseline.latencies, .99), rebuildP99 = percentile(during.latencies, .99);
        double multiplier = Double.parseDouble(System.getProperty("zones.test.rebuild-p99-multiplier", "3"));
        long additiveAllowanceNanos = Long.getLong("zones.test.rebuild-p99-additive-nanos", 2_000_000L);
        long threshold = Math.max((long) (multiplier * baselineP99), baselineP99 + additiveAllowanceNanos);
        String report = "seed_zones=" + ZONE_SEED + " seed_points=" + POINT_SEED + " zones=" + zones.size()
                + " vertices_per_zone=4 shape=square_0.001deg extent=77.4,12.8_to_77.8,13.2 target_queries_per_second=20000 window_seconds=5 each"
                + " rebuild_period_ms=500 baseline_achieved_qps=" + String.format(java.util.Locale.ROOT, "%.2f", baseline.achievedQps)
                + " baseline_p50_ns=" + percentile(baseline.latencies, .50)
                + " baseline_p99_ns=" + baselineP99 + " rebuild_achieved_qps=" + String.format(java.util.Locale.ROOT, "%.2f", during.achievedQps)
                + " rebuild_p50_ns=" + percentile(during.latencies, .50)
                + " rebuild_p99_ns=" + rebuildP99 + " rebuilds=" + rebuilds.get() + " threshold_p99_ns=" + threshold
                + " threshold_multiplier=" + multiplier + " threshold_additive_nanos=" + additiveAllowanceNanos;
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/perf-report.txt"), report + System.lineSeparator());
        System.out.println(report);
        assertThat(rebuilds.get()).isPositive();
        assertThat(rebuildP99).isLessThanOrEqualTo(threshold);
    }

    private static List<Zone> syntheticZones() {
        java.util.Random random = new java.util.Random(ZONE_SEED);
        List<Zone> zones = new ArrayList<>(5_000);
        for (int i = 0; i < 5_000; i++) {
            double lon = EXTENT.minLon() + (i % 100) * .004 + random.nextDouble() * .0001;
            double lat = EXTENT.minLat() + (i / 100) * .008 + random.nextDouble() * .0001;
            PolygonShape shape = PolygonShape.of(List.of(new com.polyzones.core.geo.LonLat(lon, lat),
                    new com.polyzones.core.geo.LonLat(lon + .001, lat), new com.polyzones.core.geo.LonLat(lon + .001, lat + .001),
                    new com.polyzones.core.geo.LonLat(lon, lat + .001), new com.polyzones.core.geo.LonLat(lon, lat)),
                    List.of(), GeometryTolerance.DEFAULT);
            zones.add(new Zone(new ZoneKey(UUID.nameUUIDFromBytes((ZONE_SEED + ":" + i).getBytes(java.nio.charset.StandardCharsets.UTF_8))),
                    1, ZoneType.TARIFF, 0, shape));
        }
        return List.copyOf(zones);
    }

    private static Measured measure(SnapshotRegistry registry, double[] points, int offset, int seconds) {
        int qps = 20_000;
        int operations = qps * seconds;
        List<Long> latency = new ArrayList<>(operations);
        long[] checksum = {0};
        IntConsumer consumer = ordinal -> checksum[0] += ordinal + 1L;
        long start = System.nanoTime();
        for (int i = 0; i < operations; i++) {
            long due = start + (long) i * 1_000_000_000L / qps;
            while (System.nanoTime() < due) LockSupport.parkNanos(Math.min(100_000L, due - System.nanoTime()));
            ZoneSnapshot snapshot = registry.latest(TENANT);
            int point = ((offset + i) * 2) % points.length;
            long queryStart = System.nanoTime();
            snapshot.index().forEachContaining(points[point], points[point + 1], consumer);
            latency.add(System.nanoTime() - queryStart);
        }
        if (checksum[0] == Long.MIN_VALUE) throw new AssertionError("Query result checksum overflow");
        long elapsed = System.nanoTime() - start;
        return new Measured(latency.stream().mapToLong(Long::longValue).toArray(), operations / (elapsed / 1_000_000_000.0));
    }

    private static long percentile(long[] values, double percentile) {
        long[] sorted = values.clone();
        java.util.Arrays.sort(sorted);
        return sorted[Math.min(sorted.length - 1, (int) Math.ceil(percentile * sorted.length) - 1)];
    }
    private record Measured(long[] latencies, double achievedQps) { }
}
