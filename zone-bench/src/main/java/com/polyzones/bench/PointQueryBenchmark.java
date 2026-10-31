package com.polyzones.bench;

import com.polyzones.core.geo.BoundingBox;
import com.polyzones.core.geo.GeometryTolerance;
import com.polyzones.core.index.NaiveZoneIndex;
import com.polyzones.core.index.RTreeZoneIndex;
import com.polyzones.core.index.SnapshotBuilder;
import com.polyzones.core.index.ZoneSnapshot;
import com.polyzones.core.index.ZoneIndex;
import com.polyzones.core.index.synthetic.SyntheticPoints;
import com.polyzones.core.index.synthetic.SyntheticZones;
import com.polyzones.core.zone.Zone;
import java.util.List;
import java.util.function.IntConsumer;
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
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/** Point operation is a complete in-process polygon containment query. */
@State(Scope.Benchmark)
@Warmup(iterations=5,time=2)
@Measurement(iterations=10,time=2)
@Fork(2)
public class PointQueryBenchmark {
    private static final BoundingBox METRO=new BoundingBox(77.4,12.8,77.8,13.2);
    @Param({"naive","rtree"}) public String index;
    @Param({"100","1000","5000"}) public int polygons;
    @Param({"16","500"}) public int verticesPerPolygon;
    private ZoneIndex zoneIndex;
    private double[] points;
    private BoundingBox[] queryBoxes;
    private ZoneSnapshot snapshot;

    @Setup(Level.Trial) public void setup() {
        long seed=0x6d697373L;
        List<Zone> zones=SyntheticZones.generate(seed,polygons,verticesPerPolygon,.12,0.1,METRO);
        snapshot=SnapshotBuilder.build(java.util.UUID.nameUUIDFromBytes("jmh-tenant".getBytes(java.nio.charset.StandardCharsets.UTF_8)),1,zones);
        zoneIndex=index.equals("naive")?new NaiveZoneIndex(snapshot.zones(),GeometryTolerance.DEFAULT):snapshot.index();
        points=SyntheticPoints.generate(seed^0x55aaL,65_536,METRO,.1,zones);
        queryBoxes=new BoundingBox[points.length/2];
        for(int i=0;i<queryBoxes.length;i++) queryBoxes[i]=new BoundingBox(points[i*2],points[i*2+1],points[i*2],points[i*2+1]);
    }
    @State(Scope.Thread) public static class Cursor implements IntConsumer {
        int next; long checksum;
        int nextPoint() { return (next++ & 65_535)*2; }
        @Override public void accept(int ordinal) { checksum+=ordinal+1L; }
    }
    @Benchmark @BenchmarkMode(Mode.Throughput) @Threads(1) public void queryThroughputSingle(Cursor cursor,Blackhole blackhole) { int p=cursor.nextPoint(); blackhole.consume(zoneIndex.query(points[p],points[p+1])); }
    @Benchmark @BenchmarkMode(Mode.Throughput) @Threads(Threads.MAX) public void queryThroughputMax(Cursor cursor,Blackhole blackhole) { int p=cursor.nextPoint(); blackhole.consume(zoneIndex.query(points[p],points[p+1])); }
    @Benchmark @BenchmarkMode(Mode.SampleTime) @Threads(1) public void querySampleTimeSingle(Cursor cursor,Blackhole blackhole) { int p=cursor.nextPoint(); blackhole.consume(zoneIndex.query(points[p],points[p+1])); }
    @Benchmark @BenchmarkMode(Mode.SampleTime) @Threads(Threads.MAX) public void querySampleTimeMax(Cursor cursor,Blackhole blackhole) { int p=cursor.nextPoint(); blackhole.consume(zoneIndex.query(points[p],points[p+1])); }
    /** Fixed thread-scoped consumer means the callback itself is not allocated per operation. */
    @Benchmark @BenchmarkMode(Mode.Throughput) @Threads(Threads.MAX) public void forEachContainingAllocationProbe(Cursor cursor,Blackhole blackhole) { int p=cursor.nextPoint(); zoneIndex.forEachContaining(points[p],points[p+1],cursor); blackhole.consume(cursor.checksum); }
    @Benchmark @BenchmarkMode(Mode.Throughput) @Threads(Threads.MAX) public void candidateRetrieval(Cursor cursor,Blackhole blackhole) { int p=cursor.nextPoint(); zoneIndex.forEachCandidate(queryBoxes[p/2],cursor); blackhole.consume(cursor.checksum); }
    public ZoneSnapshot snapshot() { return snapshot; }
}
