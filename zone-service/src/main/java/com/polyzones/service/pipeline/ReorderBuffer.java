package com.polyzones.service.pipeline;

import com.polyzones.core.trip.Ping;
import com.polyzones.core.trip.SkippedRange;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/** Bounded per-trip reorder state; skipped intervals remain authoritative for LATE classification. */
public final class ReorderBuffer {
    public enum Disposition { BUFFERED, READY, DUPLICATE, LATE, FULL }
    public record Offer(Disposition disposition, List<Ping> ready, SkippedRange skipped) {
        public Offer { ready = List.copyOf(ready); }
    }
    private record Pending(Ping ping, Instant receivedAt) { }

    private final int maxBuffered;
    private final Duration maxHold;
    private final Clock clock;
    private final TreeMap<Long, Pending> buffered = new TreeMap<>();
    private final List<SkippedRange> skipped = new ArrayList<>();
    private long nextSeq;
    private boolean exhausted;

    public ReorderBuffer(long firstSeq, int maxBuffered, Duration maxHold, Clock clock, List<SkippedRange> recoveredSkipped) {
        if (firstSeq < 0 || maxBuffered < 1 || maxHold == null || maxHold.isNegative() || clock == null)
            throw new IllegalArgumentException("Invalid reorder buffer configuration");
        this.nextSeq = firstSeq;
        this.maxBuffered = maxBuffered;
        this.maxHold = maxHold;
        this.clock = clock;
        if (recoveredSkipped != null) for (SkippedRange range : recoveredSkipped) {
            List<SkippedRange> merged = SkippedRange.merge(this.skipped, range);
            this.skipped.clear(); this.skipped.addAll(merged);
        }
    }

    public synchronized Offer offer(Ping ping) {
        if (exhausted) return new Offer(isSkipped(ping.seq()) ? Disposition.LATE : Disposition.DUPLICATE, List.of(), null);
        if (ping.seq() < nextSeq) return new Offer(isSkipped(ping.seq()) ? Disposition.LATE : Disposition.DUPLICATE, List.of(), null);
        if (buffered.containsKey(ping.seq())) return new Offer(Disposition.DUPLICATE, List.of(), null);
        if (ping.seq() > nextSeq) {
            if (buffered.size() >= maxBuffered) return new Offer(Disposition.FULL, List.of(), null);
            buffered.put(ping.seq(), new Pending(ping, clock.instant()));
            return new Offer(Disposition.BUFFERED, List.of(), null);
        }
        List<Ping> ready = new ArrayList<>();
        ready.add(ping);
        advanceNext();
        drain(ready);
        return new Offer(Disposition.READY, ready, null);
    }

    public synchronized Offer releaseExpired() {
        if (buffered.isEmpty()) return new Offer(Disposition.BUFFERED, List.of(), null);
        Pending first = buffered.firstEntry().getValue();
        if (Duration.between(first.receivedAt(), clock.instant()).compareTo(maxHold) <= 0)
            return new Offer(Disposition.BUFFERED, List.of(), null);
        long received = buffered.firstKey();
        SkippedRange gap = new SkippedRange(nextSeq, received - 1);
        List<SkippedRange> merged = SkippedRange.merge(skipped, gap);
        skipped.clear(); skipped.addAll(merged);
        nextSeq = received;
        List<Ping> ready = new ArrayList<>();
        drain(ready);
        return new Offer(Disposition.READY, ready, gap);
    }

    /** Read-only admission classification; it does not advance the sequence frontier. */
    public synchronized Disposition classify(long seq) {
        if (seq < 0) return Disposition.FULL;
        if (exhausted || seq < nextSeq) return isSkipped(seq) ? Disposition.LATE : Disposition.DUPLICATE;
        if (buffered.containsKey(seq)) return Disposition.DUPLICATE;
        if (seq > nextSeq && buffered.size() >= maxBuffered) return Disposition.FULL;
        return Disposition.READY;
    }

    private void drain(List<Ping> ready) {
        while (true) {
            Pending next = buffered.remove(nextSeq);
            if (next == null) return;
            ready.add(next.ping());
            advanceNext();
        }
    }

    private void advanceNext() {
        if (nextSeq == Long.MAX_VALUE) exhausted = true;
        else nextSeq++;
    }

    private boolean isSkipped(long seq) { return skipped.stream().anyMatch(range -> range.contains(seq)); }
    public synchronized long nextSeq() { return nextSeq; }
    public synchronized int bufferedCount() { return buffered.size(); }
    public synchronized List<SkippedRange> skippedRanges() { return List.copyOf(skipped); }
}
