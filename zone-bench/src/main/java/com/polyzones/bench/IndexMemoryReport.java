package com.polyzones.bench;

import com.polyzones.core.geo.BoundingBox;
import com.polyzones.core.index.SnapshotBuilder;
import com.polyzones.core.index.ZoneSnapshot;
import com.polyzones.core.index.synthetic.SyntheticZones;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Heap-delta approximation after full GC; reports retained snapshot plus its reachable graph. */
public final class IndexMemoryReport {
    private static final BoundingBox METRO=new BoundingBox(77.4,12.8,77.8,13.2);
    private IndexMemoryReport() { }
    public static void main(String[] args) throws Exception {
        Path output=Path.of(args.length==0?"docs/benchmarks/memory.csv":args[0]); List<String> rows=new ArrayList<>();
        rows.add("polygons,vertices_per_polygon,seed,retained_heap_bytes,build_stats_approx_bytes,measurement_method");
        for(int count:new int[]{1_000,5_000,20_000,100_000}) rows.add(measure(count,16));
        rows.add(measure(5_000,500));
        Files.createDirectories(output.getParent()); Files.write(output,rows);
        System.out.println("Wrote "+rows.size()+" memory records to "+output+" using Runtime heap delta after System.gc().");
    }
    private static String measure(int count,int vertices) throws InterruptedException {
        long seed=0x4d454dL+count*31L+vertices;
        forceGc(); long before=usedHeap(); ZoneSnapshot snapshot=build(count,vertices,seed); forceGc(); long after=usedHeap();
        long delta=Math.max(0,after-before); long approximate=snapshot.buildStats().approxBytes();
        System.out.printf("memory polygons=%d vertices=%d seed=%d retainedHeapDelta=%d buildApprox=%d%n",count,vertices,seed,delta,approximate);
        return count+","+vertices+","+seed+","+delta+","+approximate+",Runtime totalMemory-freeMemory delta after full GC; approximate and process-sensitive";
    }
    private static ZoneSnapshot build(int count,int vertices,long seed) {
        var zones=SyntheticZones.generate(seed,count,vertices,.12,.1,METRO);
        return SnapshotBuilder.build(UUID.nameUUIDFromBytes(("memory:"+seed).getBytes(java.nio.charset.StandardCharsets.UTF_8)),1,zones);
    }
    private static long usedHeap() { Runtime r=Runtime.getRuntime(); return r.totalMemory()-r.freeMemory(); }
    private static void forceGc() throws InterruptedException { System.gc(); Thread.sleep(150); }
}
