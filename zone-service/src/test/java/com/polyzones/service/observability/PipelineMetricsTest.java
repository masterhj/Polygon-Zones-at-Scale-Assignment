package com.polyzones.service.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class PipelineMetricsTest {
    @Test void registersPhaseFiveCountersAndQueueGauge() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PipelineMetrics metrics = new PipelineMetrics(registry);
        metrics.admitted(3);
        metrics.duplicate();
        metrics.late();
        metrics.invalid();
        metrics.clockSkew();
        metrics.backpressure();
        metrics.gapSkipped();
        metrics.checkpointFailures(2);
        metrics.timeRegression();
        metrics.suspectJump();
        metrics.workerFailure();
        metrics.queued(4);

        assertThat(registry.get("ping_admitted").counter().count()).isEqualTo(3);
        assertThat(registry.get("ping_duplicate").counter().count()).isEqualTo(1);
        assertThat(registry.get("ping_rejected").tag("reason", "late").counter().count()).isEqualTo(1);
        assertThat(registry.get("ping_rejected").tag("reason", "invalid").counter().count()).isEqualTo(1);
        assertThat(registry.get("ping_rejected").tag("reason", "clock_skew").counter().count()).isEqualTo(1);
        assertThat(registry.get("ping_backpressure").counter().count()).isEqualTo(1);
        assertThat(registry.get("ping_gap_skipped").counter().count()).isEqualTo(1);
        assertThat(registry.get("checkpoint_failures").counter().count()).isEqualTo(2);
        assertThat(registry.get("time_regression").counter().count()).isEqualTo(1);
        assertThat(registry.get("suspect_jump").counter().count()).isEqualTo(1);
        assertThat(registry.get("ping_worker_failure").counter().count()).isEqualTo(1);
        assertThat(registry.get("ping_queue_depth").gauge().value()).isEqualTo(4);
        registry.close();
    }
}
