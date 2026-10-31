package com.polyzones.bench;

import com.polyzones.core.geo.BoundingBox;
import com.polyzones.core.index.SnapshotBuilder;
import com.polyzones.core.index.synthetic.SyntheticZones;
import com.polyzones.core.zone.Zone;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

@State(Scope.Thread)
@Warmup(iterations=5,time=2)
@Measurement(iterations=10,time=2)
@Fork(2)
public class IndexBuildBenchmark {
    @Param({"16","500"}) public int verticesPerPolygon;
    private List<Zone> zones;
    @Setup(Level.Trial) public void setup() { zones=SyntheticZones.generate(0x6275696c64L,5_000,verticesPerPolygon,.12,.1,new BoundingBox(77.4,12.8,77.8,13.2)); }
    @Benchmark @BenchmarkMode(Mode.SingleShotTime) public void singleShot(Blackhole blackhole) { blackhole.consume(SnapshotBuilder.build(UUID.nameUUIDFromBytes("build".getBytes(java.nio.charset.StandardCharsets.UTF_8)),1,zones)); }
    @Benchmark @BenchmarkMode(Mode.AverageTime) public void average(Blackhole blackhole) { blackhole.consume(SnapshotBuilder.build(UUID.nameUUIDFromBytes("build".getBytes(java.nio.charset.StandardCharsets.UTF_8)),1,zones)); }
}
