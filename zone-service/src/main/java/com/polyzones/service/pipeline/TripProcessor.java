package com.polyzones.service.pipeline;

import com.polyzones.core.index.ZoneSnapshot;
import com.polyzones.core.trip.AlertCandidate;
import com.polyzones.core.trip.NightWindow;
import com.polyzones.core.trip.Ping;
import com.polyzones.core.trip.ProcessResult;
import com.polyzones.core.trip.TripAttributor;
import com.polyzones.core.trip.TripCompletion;
import com.polyzones.core.trip.TripState;
import com.polyzones.core.trip.ZoneEvent;
import com.polyzones.service.observability.PipelineMetrics;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Single-shard owner of one trip's sequence buffer, attribution state and checkpoint frontier. */
public final class TripProcessor {
    public enum Status { PROCESSED, BUFFERED, DUPLICATE, LATE, BUFFER_FULL }
    public record Outcome(Status status, List<ProcessResult> applied, long durableAckedSeq) {
        public Outcome { applied = List.copyOf(applied); }
    }
    private final TripAttributor attributor;
    private final TripState state;
    private final ReorderBuffer reorder;
    private final Supplier<ZoneSnapshot> latestSnapshot;
    private final NightWindow nightWindow;
    private final ZoneId tenantZone;
    private final Clock clock;
    private final TripCheckpointWriter checkpointer;
    private final int checkpointEveryPings;
    private final Duration checkpointEvery;
    private final List<ZoneEvent> uncommittedEvents = new ArrayList<>();
    private final List<AlertCandidate> uncommittedAlerts = new ArrayList<>();
    private final List<Ping> replayable = new ArrayList<>();
    private long sinceCheckpoint;
    private Instant lastCheckpoint;
    private boolean dirty;
    private final PipelineMetrics metrics;
    private long reportedCheckpointFailures;

    public TripProcessor(TripAttributor attributor, TripState state, ReorderBuffer reorder,
                         Supplier<ZoneSnapshot> latestSnapshot, NightWindow nightWindow, ZoneId tenantZone,
                         Clock clock, TripCheckpointWriter checkpointer, int checkpointEveryPings,
                         Duration checkpointEvery) {
        this(attributor, state, reorder, latestSnapshot, nightWindow, tenantZone, clock, checkpointer,
                checkpointEveryPings, checkpointEvery, null);
    }

    public TripProcessor(TripAttributor attributor, TripState state, ReorderBuffer reorder,
                         Supplier<ZoneSnapshot> latestSnapshot, NightWindow nightWindow, ZoneId tenantZone,
                         Clock clock, TripCheckpointWriter checkpointer, int checkpointEveryPings,
                         Duration checkpointEvery, PipelineMetrics metrics) {
        if (attributor == null || state == null || reorder == null || latestSnapshot == null || nightWindow == null
                || tenantZone == null || clock == null || checkpointer == null || checkpointEveryPings < 1
                || checkpointEvery == null || checkpointEvery.isNegative() || checkpointEvery.isZero())
            throw new IllegalArgumentException("Invalid trip processor configuration");
        if (attributor.state() != state) throw new IllegalArgumentException("Processor must own the attributor's exact state instance");
        this.attributor = attributor; this.state = state; this.reorder = reorder;
        this.latestSnapshot = latestSnapshot; this.nightWindow = nightWindow; this.tenantZone = tenantZone;
        this.clock = clock; this.checkpointer = checkpointer; this.checkpointEveryPings = checkpointEveryPings;
        this.checkpointEvery = checkpointEvery; this.lastCheckpoint = clock.instant();
        this.metrics = metrics;
    }

    public synchronized Outcome accept(Ping ping) {
        if (checkpointer.hasPending()) throw new CheckpointBacklogException();
        ReorderBuffer.Offer offer = reorder.offer(ping);
        if (offer.disposition() == ReorderBuffer.Disposition.DUPLICATE) {
            state.addDuplicatePing();
            if (metrics != null) metrics.duplicate();
            return new Outcome(Status.DUPLICATE, List.of(), state.durableAckedSeq());
        }
        if (offer.disposition() == ReorderBuffer.Disposition.LATE) {
            state.addLatePing();
            if (metrics != null) metrics.late();
            return new Outcome(Status.LATE, List.of(), state.durableAckedSeq());
        }
        if (offer.disposition() == ReorderBuffer.Disposition.FULL)
            return new Outcome(Status.BUFFER_FULL, List.of(), state.durableAckedSeq());
        if (offer.disposition() == ReorderBuffer.Disposition.BUFFERED)
            return new Outcome(Status.BUFFERED, List.of(), state.durableAckedSeq());
        return processOffer(offer);
    }

    public synchronized Outcome tick() {
        if (checkpointer.hasPending()) {
            boolean recovered = checkpointer.retryPending(state);
            recordCheckpointFailures();
            if (!recovered) return new Outcome(Status.BUFFER_FULL, List.of(), state.durableAckedSeq());
            clearCommitted();
        }
        ReorderBuffer.Offer expired = reorder.releaseExpired();
        if (expired.skipped() != null) {
            state.addSkippedRange(expired.skipped().firstSeq(), expired.skipped().lastSeq());
            if (metrics != null) metrics.gapSkipped();
            dirty = true;
        }
        Outcome result = processOffer(expired);
        maybeCheckpoint(false);
        return result;
    }

    public synchronized boolean checkpoint() { return commitCheckpoint(false); }

    public synchronized TripCompletion complete(Instant endTime) {
        if (checkpointer.hasPending()) throw new CheckpointBacklogException();
        TripCompletion completion = attributor.complete(endTime);
        uncommittedEvents.addAll(completion.exitEvents());
        if (!commitCheckpoint(true)) throw new CheckpointBacklogException();
        return completion;
    }

    private Outcome processOffer(ReorderBuffer.Offer offer) {
        List<ProcessResult> applied = new ArrayList<>();
        for (Ping next : offer.ready()) {
            ProcessResult result = attributor.apply(next, latestSnapshot.get(), nightWindow, tenantZone);
            applied.add(result);
            uncommittedEvents.addAll(result.events());
            uncommittedAlerts.addAll(result.alerts());
            if (metrics != null) {
                if (result.timeRegression()) metrics.timeRegression();
                if (result.suspectJump()) metrics.suspectJump();
            }
            replayable.add(next);
            sinceCheckpoint++;
            dirty = true;
        }
        maybeCheckpoint(false);
        Status status = offer.skipped() != null ? Status.PROCESSED
                : applied.isEmpty() ? Status.BUFFERED : Status.PROCESSED;
        return new Outcome(status, applied, state.durableAckedSeq());
    }

    private void maybeCheckpoint(boolean force) {
        if (force || sinceCheckpoint >= checkpointEveryPings
                || Duration.between(lastCheckpoint, clock.instant()).compareTo(checkpointEvery) >= 0) commitCheckpoint(force);
    }

    private boolean commitCheckpoint(boolean force) {
        if (!force && !dirty) return true;
        boolean committed = checkpointer.write(state, uncommittedEvents, uncommittedAlerts, replayable);
        recordCheckpointFailures();
        if (committed) clearCommitted();
        return committed;
    }

    private void clearCommitted() {
        uncommittedEvents.clear(); uncommittedAlerts.clear(); replayable.clear(); sinceCheckpoint = 0;
        lastCheckpoint = clock.instant(); dirty = false;
    }

    private void recordCheckpointFailures() {
        if (metrics == null) return;
        long failures = checkpointer.failures();
        if (failures > reportedCheckpointFailures) metrics.checkpointFailures(failures - reportedCheckpointFailures);
        reportedCheckpointFailures = failures;
    }

    public long durableAckedSeq() { return state.durableAckedSeq(); }
    public int bufferedCount() { return reorder.bufferedCount(); }
    public long checkpointFailures() { return checkpointer.failures(); }
    public long latePings() { return state.latePings(); }
    public long duplicatePings() { return state.duplicatePings(); }
    public long gapsSkipped() { return state.gapsSkipped(); }

    public static final class CheckpointBacklogException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public CheckpointBacklogException() { super("Trip checkpoint backlog is at capacity"); }
    }
}
