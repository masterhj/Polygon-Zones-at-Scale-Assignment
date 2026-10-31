package com.polyzones.bench;

import com.polyzones.core.geo.BoundingBox;
import com.polyzones.core.index.ZoneSnapshot;
import com.polyzones.core.index.SnapshotBuilder;
import com.polyzones.core.index.synthetic.SyntheticPoints;
import com.polyzones.core.index.synthetic.SyntheticZones;
import com.polyzones.core.zone.Zone;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.locks.LockSupport;
import java.util.function.IntConsumer;
import org.HdrHistogram.ConcurrentHistogram;

/** Paces an in-process point-query stream open-loop at the D-22 target. */
public final class PointQueryLoadTest {
    private static final int TARGET_QPS=50_000;
    private static final int DURATION_SECONDS=60;
    private static final int SLOT_COUNT=65_536;
    private static final int POLYGON_COUNT=5_000;
    private static final int VERTICES_PER_POLYGON=16;
    private static final long ZONE_SEED=0x6c6f6164L;
    private static final long POINT_SEED=0x706f696e74L;
    private static final BoundingBox METRO=new BoundingBox(77.4,12.8,77.8,13.2);
    private PointQueryLoadTest() { }
    public static void main(String[] args) throws Exception {
        String mode=args.length==0?"baseline":args[0]; if(!mode.equals("baseline")&&!mode.equals("rebuild")) throw new IllegalArgumentException("mode must be baseline or rebuild");
        List<Zone> zones=SyntheticZones.generate(ZONE_SEED,POLYGON_COUNT,VERTICES_PER_POLYGON,.12,.1,METRO);
        ZoneSnapshot initial=SnapshotBuilder.build(UUID.nameUUIDFromBytes("load-tenant".getBytes(java.nio.charset.StandardCharsets.UTF_8)),1,zones);
        double[] points=SyntheticPoints.generate(POINT_SEED,TARGET_QPS*DURATION_SECONDS,METRO,.1,zones);
        run(mode,zones,initial,points);
    }
    private static void run(String mode,List<Zone> zones,ZoneSnapshot initial,double[] points) throws Exception {
        int readers=Math.max(1,Runtime.getRuntime().availableProcessors());
        ArrayBlockingQueue<QuerySlot> available=new ArrayBlockingQueue<>(SLOT_COUNT), pending=new ArrayBlockingQueue<>(SLOT_COUNT);
        for(int i=0;i<SLOT_COUNT;i++) available.add(new QuerySlot());
        AtomicReference<ZoneSnapshot> current=new AtomicReference<>(initial); AtomicBoolean stop=new AtomicBoolean();
        AtomicLong completed=new AtomicLong(), completedWithinWindow=new AtomicLong(),dropped=new AtomicLong(); AtomicInteger queueHighWater=new AtomicInteger();
        ConcurrentHistogram histogram=new ConcurrentHistogram(1,180_000_000_000L,3);
        ConcurrentHistogram scheduleToDone=new ConcurrentHistogram(1,180_000_000_000L,3);
        AtomicInteger workerFailures=new AtomicInteger();
        CountDownLatch warmupReady=new CountDownLatch(readers);
        Reader[] workers=new Reader[readers]; Thread[] readerThreads=new Thread[readers];
        for(int i=0;i<readers;i++) { workers[i]=new Reader(pending,available,current,stop,histogram,scheduleToDone,completed,completedWithinWindow,points,warmupReady); readerThreads[i]=new Thread(workers[i],"point-query-reader-"+i); readerThreads[i].setUncaughtExceptionHandler((thread,error)->{workerFailures.incrementAndGet();error.printStackTrace(System.err);}); readerThreads[i].start(); }
        warmupReady.await();
        long start=System.nanoTime(), windowEnd=start+DURATION_SECONDS*1_000_000_000L; Reader.runEnd=windowEnd;
        AtomicBoolean rebuildStop=new AtomicBoolean(); Thread rebuilder=null;
        if(mode.equals("rebuild")) {
            rebuilder=new Thread(()->{ long version=1; while(!rebuildStop.get()) { LockSupport.parkNanos(2_000_000_000L); if(rebuildStop.get())break; ZoneSnapshot next=SnapshotBuilder.build(initial.tenantId(),++version,zones); current.set(next); } },"snapshot-rebuilder");
            rebuilder.start();
        }
        long interval=1_000_000_000L/TARGET_QPS;
        long scheduled=(long)TARGET_QPS*DURATION_SECONDS,accepted=0;
        for(long seq=0;seq<scheduled;seq++) {
            long due=start+seq*interval;
            while(System.nanoTime()<due) LockSupport.parkNanos(Math.min(100_000L,due-System.nanoTime()));
            QuerySlot slot=available.poll();
            if(slot==null) { dropped.incrementAndGet(); continue; }
            slot.deadline=due; slot.lon=points[(int)(seq*2)]; slot.lat=points[(int)(seq*2+1)];
            if(pending.offer(slot)) { accepted++; queueHighWater.accumulateAndGet(pending.size(),Math::max); }
            else { dropped.incrementAndGet(); available.offer(slot); }
        }
        long scheduledEnd=System.nanoTime(); stop.set(true);
        for(Thread thread:readerThreads) thread.join();
        rebuildStop.set(true); if(rebuilder!=null) { LockSupport.unpark(rebuilder); rebuilder.join(); }
        double qps=completedWithinWindow.get()/(DURATION_SECONDS*1.0);
        System.out.println("workload=in-process point-containment (one indexed query; not HTTP or durable processing)");
        System.out.println("mode="+mode+" target_qps="+TARGET_QPS+" duration_seconds="+DURATION_SECONDS+" polygons="+POLYGON_COUNT+" vertices_per_polygon="+VERTICES_PER_POLYGON+" zone_seed="+ZONE_SEED+" point_seed="+POINT_SEED+" readers="+readers);
        System.out.println("scheduled="+scheduled+" accepted_to_queue="+accepted+" dropped_overload="+dropped.get()+" completed_total="+completed.get()+" completed_by_window_end="+completedWithinWindow.get()+" queue_capacity="+SLOT_COUNT+" queue_high_water="+queueHighWater.get());
        System.out.println("worker_failures="+workerFailures.get());
        System.out.println("achieved_completed_qps="+String.format(java.util.Locale.ROOT,"%.2f",qps)+" schedule_seconds="+String.format(java.util.Locale.ROOT,"%.3f",(scheduledEnd-start)/1e9));
        System.out.println("service_latency_scope=query-start-to-completion for accepted queries; expected_interval_ns="+interval+" HdrHistogram coordinated-omission correction enabled");
        System.out.println("service_p50_ns="+histogram.getValueAtPercentile(50)+" service_p90_ns="+histogram.getValueAtPercentile(90)+" service_p99_ns="+histogram.getValueAtPercentile(99)+" service_p99_9_ns="+histogram.getValueAtPercentile(99.9)+" service_max_ns="+histogram.getMaxValue());
        System.out.println("schedule_to_done_scope=scheduled-deadline-to-query-completion; no correction applied because arrivals are already paced open-loop");
        System.out.println("schedule_to_done_p50_ns="+scheduleToDone.getValueAtPercentile(50)+" schedule_to_done_p90_ns="+scheduleToDone.getValueAtPercentile(90)+" schedule_to_done_p99_ns="+scheduleToDone.getValueAtPercentile(99)+" schedule_to_done_p99_9_ns="+scheduleToDone.getValueAtPercentile(99.9)+" schedule_to_done_max_ns="+scheduleToDone.getMaxValue());
        System.out.println("snapshot_versions_built="+(mode.equals("rebuild")?current.get().setVersion():1)+" p99_definition=difference from baseline run required in report");
        long checksum=0; for(Reader reader:workers) checksum+=reader.checksum; System.out.println("result_checksum="+checksum);
    }
    private static final class QuerySlot { long deadline; double lon,lat; }
    private static final class Reader implements Runnable,IntConsumer {
        final ArrayBlockingQueue<QuerySlot> pending,available; final AtomicReference<ZoneSnapshot> current; final AtomicBoolean stop;
        final ConcurrentHistogram histogram,scheduleToDone; final AtomicLong completed,within; long checksum;
        final double[] warmupPoints; final CountDownLatch warmupReady;
        Reader(ArrayBlockingQueue<QuerySlot> pending,ArrayBlockingQueue<QuerySlot> available,AtomicReference<ZoneSnapshot> current,AtomicBoolean stop,ConcurrentHistogram histogram,ConcurrentHistogram scheduleToDone,AtomicLong completed,AtomicLong within,double[] warmupPoints,CountDownLatch warmupReady) { this.pending=pending;this.available=available;this.current=current;this.stop=stop;this.histogram=histogram;this.scheduleToDone=scheduleToDone;this.completed=completed;this.within=within;this.warmupPoints=warmupPoints;this.warmupReady=warmupReady; }
        @Override public void run() {
            for(int i=0;i<10_000;i++) current.get().index().forEachContaining(warmupPoints[(i*2)%warmupPoints.length],warmupPoints[(i*2+1)%warmupPoints.length],this);
            warmupReady.countDown();
            while(!stop.get()||!pending.isEmpty()) {
                QuerySlot slot;
                try { slot=pending.poll(100,java.util.concurrent.TimeUnit.MILLISECONDS); } catch(InterruptedException e) { Thread.currentThread().interrupt(); return; }
                if(slot==null) continue;
                long queryStart=System.nanoTime();
                current.get().index().forEachContaining(slot.lon,slot.lat,this);
                long done=System.nanoTime(),serviceLatency=Math.max(1,done-queryStart);
                histogram.recordValueWithExpectedInterval(serviceLatency,1_000_000_000L/TARGET_QPS);
                scheduleToDone.recordValue(Math.max(1,done-slot.deadline));
                completed.incrementAndGet(); if(done<=runEnd)within.incrementAndGet(); available.offer(slot);
            }
        }
        @Override public void accept(int ordinal) { checksum+=ordinal+1L; }
        private static volatile long runEnd;
    }
}
