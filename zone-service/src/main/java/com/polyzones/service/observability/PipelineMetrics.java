package com.polyzones.service.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;

/** Metrics required to observe Phase 5 admission, sequencing and checkpoint behavior. */
public final class PipelineMetrics {
    private final Counter admitted;
    private final Counter duplicate;
    private final Counter late;
    private final Counter invalid;
    private final Counter clockSkew;
    private final Counter backpressure;
    private final Counter gapSkipped;
    private final Counter checkpointFailures;
    private final Counter timeRegressions;
    private final Counter suspectJumps;
    private final Counter workerFailures;
    private final AtomicLong queueDepth = new AtomicLong();

    public PipelineMetrics(MeterRegistry registry) {
        if (registry == null) throw new IllegalArgumentException("MeterRegistry is required");
        admitted = registry.counter("ping_admitted");
        duplicate = registry.counter("ping_duplicate");
        late = registry.counter("ping_rejected", "reason", "late");
        invalid = registry.counter("ping_rejected", "reason", "invalid");
        clockSkew = registry.counter("ping_rejected", "reason", "clock_skew");
        backpressure = registry.counter("ping_backpressure");
        gapSkipped = registry.counter("ping_gap_skipped");
        checkpointFailures = registry.counter("checkpoint_failures");
        timeRegressions = registry.counter("time_regression");
        suspectJumps = registry.counter("suspect_jump");
        workerFailures = registry.counter("ping_worker_failure");
        registry.gauge("ping_queue_depth", queueDepth);
    }

    public void admitted(long count) { admitted.increment(count); }
    public void duplicate() { duplicate.increment(); }
    public void late() { late.increment(); }
    public void invalid() { invalid.increment(); }
    public void clockSkew() { clockSkew.increment(); }
    public void backpressure() { backpressure.increment(); }
    public void gapSkipped() { gapSkipped.increment(); }
    public void checkpointFailures(long count) { checkpointFailures.increment(count); }
    public void timeRegression() { timeRegressions.increment(); }
    public void suspectJump() { suspectJumps.increment(); }
    public void workerFailure() { workerFailures.increment(); }
    public void queued(long count) { queueDepth.addAndGet(count); }
    public double queueDepth() { return queueDepth.get(); }
}
