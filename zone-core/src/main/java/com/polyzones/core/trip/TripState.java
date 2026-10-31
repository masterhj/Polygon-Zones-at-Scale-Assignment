package com.polyzones.core.trip;

import com.polyzones.core.geo.LonLat;
import com.polyzones.core.zone.ZoneKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Mutable single-writer state for one trip; callers capture it only on that trip's shard. */
public final class TripState {
    private final UUID tenantId;
    private final UUID tripId;
    private final long pinnedSetVersion;
    private LonLat lastPoint;
    private Instant lastEventTime;
    private long processedSeq = -1;
    private long durableAckedSeq = -1;
    private long nextTransitionOrdinal;
    private long gapsSkipped;
    private long latePings;
    private long duplicatePings;
    private long timeRegressions;
    private long suspectJumps;
    private double totalMeters;
    private long lastSafetySetVersion = -1;
    private final List<SkippedRange> skippedRanges = new ArrayList<>();
    private final TreeMap<String, Double> distanceMeters = new TreeMap<>();
    private final TreeMap<ZoneKey, OpenMembership> billingMemberships = new TreeMap<>();
    private final TreeMap<ZoneKey, OpenMembership> noGoMemberships = new TreeMap<>();
    private boolean completed;

    public TripState(UUID tenantId, UUID tripId, long pinnedSetVersion) {
        if (tenantId == null || tripId == null || pinnedSetVersion < 0) throw new IllegalArgumentException("Invalid trip state key");
        this.tenantId = tenantId;
        this.tripId = tripId;
        this.pinnedSetVersion = pinnedSetVersion;
    }

    public static TripState restore(TripStateCheckpoint checkpoint) {
        if (checkpoint == null || checkpoint.processedSeq() < -1 || checkpoint.durableAckedSeq() < -1
                || checkpoint.durableAckedSeq() > checkpoint.processedSeq() || checkpoint.nextTransitionOrdinal() < 0
                || !Double.isFinite(checkpoint.totalMeters()) || checkpoint.totalMeters() < 0)
            throw new IllegalArgumentException("Invalid trip checkpoint");
        TripState restored = new TripState(checkpoint.tenantId(), checkpoint.tripId(), checkpoint.pinnedSetVersion());
        restored.lastPoint = checkpoint.lastPoint();
        restored.lastEventTime = checkpoint.lastEventTime();
        restored.processedSeq = checkpoint.processedSeq();
        restored.durableAckedSeq = checkpoint.durableAckedSeq();
        restored.nextTransitionOrdinal = checkpoint.nextTransitionOrdinal();
        restored.gapsSkipped = checkpoint.gapsSkipped();
        restored.latePings = checkpoint.latePings();
        restored.timeRegressions = checkpoint.timeRegressions();
        restored.suspectJumps = checkpoint.suspectJumps();
        restored.totalMeters = checkpoint.totalMeters();
        restored.lastSafetySetVersion = checkpoint.lastSafetySetVersion();
        restored.skippedRanges.addAll(checkpoint.skippedRanges());
        restored.distanceMeters.putAll(checkpoint.distanceMeters());
        restored.billingMemberships.putAll(checkpoint.billingMemberships());
        restored.noGoMemberships.putAll(checkpoint.noGoMemberships());
        restored.completed = checkpoint.completed();
        return restored;
    }

    public TripStateCheckpoint checkpoint() {
        return new TripStateCheckpoint(tenantId, tripId, pinnedSetVersion, lastPoint, lastEventTime, processedSeq,
                durableAckedSeq, nextTransitionOrdinal, gapsSkipped, latePings, timeRegressions, suspectJumps,
                totalMeters, lastSafetySetVersion, skippedRanges, distanceMeters, billingMemberships,
                noGoMemberships, completed);
    }

    public UUID tenantId() { return tenantId; }
    public UUID tripId() { return tripId; }
    public long pinnedSetVersion() { return pinnedSetVersion; }
    public LonLat lastPoint() { return lastPoint; }
    public Instant lastEventTime() { return lastEventTime; }
    public long processedSeq() { return processedSeq; }
    public long durableAckedSeq() { return durableAckedSeq; }
    public long nextTransitionOrdinal() { return nextTransitionOrdinal; }
    public long gapsSkipped() { return gapsSkipped; }
    public long latePings() { return latePings; }
    public long duplicatePings() { return duplicatePings; }
    public long timeRegressions() { return timeRegressions; }
    public long suspectJumps() { return suspectJumps; }
    public double totalMeters() { return totalMeters; }
    public long lastSafetySetVersion() { return lastSafetySetVersion; }
    public boolean completed() { return completed; }
    public Map<String, Double> distanceMeters() { return Collections.unmodifiableMap(new TreeMap<>(distanceMeters)); }
    public Map<ZoneKey, OpenMembership> billingMemberships() { return Collections.unmodifiableMap(new TreeMap<>(billingMemberships)); }
    public Map<ZoneKey, OpenMembership> noGoMemberships() { return Collections.unmodifiableMap(new TreeMap<>(noGoMemberships)); }
    public List<SkippedRange> skippedRanges() { return List.copyOf(skippedRanges); }
    public boolean wasSkipped(long seq) { return skippedRanges.stream().anyMatch(range -> range.contains(seq)); }

    public void acknowledgeDurable(long frontier) {
        if (frontier < durableAckedSeq || frontier > processedSeq) throw new IllegalArgumentException("Invalid durable acknowledgement frontier");
        durableAckedSeq = frontier;
    }

    public void addSkippedRange(long firstSeq, long lastSeq) {
        SkippedRange incoming = new SkippedRange(firstSeq, lastSeq);
        List<SkippedRange> merged = SkippedRange.merge(skippedRanges, incoming);
        skippedRanges.clear(); skippedRanges.addAll(merged);
        gapsSkipped = Math.addExact(gapsSkipped, 1);
    }

    void lastPoint(LonLat value) { lastPoint = value; }
    void lastEventTime(Instant value) { lastEventTime = value; }
    void processedSeq(long value) {
        if (value <= processedSeq) throw new IllegalArgumentException("Processed sequence must increase");
        processedSeq = value;
    }
    void lastSafetySetVersion(long value) { lastSafetySetVersion = value; }
    public void addLatePing() { latePings++; }
    public void addDuplicatePing() { duplicatePings++; }
    void addTimeRegression() { timeRegressions++; }
    void addSuspectJump() { suspectJumps++; }
    void addMeters(String key, double meters) { distanceMeters.merge(key, meters, Double::sum); }
    void addTotalMeters(double meters) { totalMeters += meters; }
    TreeMap<ZoneKey, OpenMembership> mutableBillingMemberships() { return billingMemberships; }
    TreeMap<ZoneKey, OpenMembership> mutableNoGoMemberships() { return noGoMemberships; }
    long nextOrdinal() { return ++nextTransitionOrdinal; }
    void markCompleted() { completed = true; }
}
