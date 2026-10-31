package com.polyzones.service.pipeline;

import com.polyzones.core.trip.TripState;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** Serial checkpoint writer. Failed retries retain and retry the exact same captured snapshot. */
public final class TripCheckpointWriter {
    private final CheckpointRepository repository;
    private final int maxAttempts;
    private final AtomicLong failures = new AtomicLong();
    private long committedFrontier = -1;
    private CheckpointSnapshot pending;

    public TripCheckpointWriter(CheckpointRepository repository) { this(repository, 3); }

    public TripCheckpointWriter(CheckpointRepository repository, int maxAttempts) {
        if (repository == null || maxAttempts < 1) throw new IllegalArgumentException("Invalid checkpoint writer configuration");
        this.repository = repository;
        this.maxAttempts = maxAttempts;
    }

    public synchronized boolean write(TripState state, List<com.polyzones.core.trip.ZoneEvent> events,
                                      List<com.polyzones.core.trip.AlertCandidate> alerts,
                                      List<com.polyzones.core.trip.Ping> replayable) {
        if (state.processedSeq() < committedFrontier) throw new IllegalArgumentException("Stale checkpoint frontier");
        if (pending == null) pending = CheckpointSnapshot.capture(state, events, alerts, replayable);
        else if (pending.processedSeq() != state.processedSeq()) return false;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            try {
                repository.commit(pending);
                committedFrontier = pending.processedSeq();
                if (committedFrontier >= 0) state.acknowledgeDurable(committedFrontier);
                pending = null;
                return true;
            } catch (Exception failure) {
                failures.incrementAndGet();
            }
        }
        return false;
    }

    public synchronized boolean retryPending(TripState state) {
        if (pending == null) return true;
        return write(state, pending.events(), pending.alerts(), pending.replayablePings());
    }

    public long failures() { return failures.get(); }
    public synchronized long committedFrontier() { return committedFrontier; }
    public synchronized boolean hasPending() { return pending != null; }
}
