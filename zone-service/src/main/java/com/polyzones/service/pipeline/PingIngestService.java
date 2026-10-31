package com.polyzones.service.pipeline;

import com.polyzones.core.trip.Ping;
import com.polyzones.service.observability.PipelineMetrics;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

/** Bounded, single-writer-per-shard admission. ACCEPTED means queued, not processed or durable. */
public final class PingIngestService implements AutoCloseable {
    public enum Status { ACCEPTED, REJECTED_INVALID, REJECTED_CLOCK_SKEW }
    public record ItemResult(long seq, Status status) { }
    public record Admission(List<ItemResult> items, long durableAckedSeq) {
        public Admission { items = List.copyOf(items); }
    }
    private record Envelope(UUID tenantId, Ping ping, BackpressureGuard.Reservation reservation,
                            CompletableFuture<Void> barrier) { }
    private final ArrayBlockingQueue<Envelope>[] queues;
    private final Thread[] workers;
    private final BackpressureGuard capacity;
    private final Clock clock;
    private final Duration maxAge;
    private final Duration futureSkew;
    private final BiConsumer<UUID, Ping> processor;
    private final BiFunction<UUID, UUID, Long> durableAckLookup;
    private final PipelineMetrics metrics;
    private final AtomicLong workerFailures = new AtomicLong();
    private final AtomicReference<RuntimeException> workerFailure = new AtomicReference<>();
    private volatile boolean accepting = true;

    public PingIngestService(int shardCount, int queueCapacity, Clock clock, Duration maxAge,
                             Duration futureSkew, BiConsumer<UUID, Ping> processor) {
        this(shardCount, queueCapacity, clock, maxAge, futureSkew, processor, (tenant, trip) -> -1L, null);
    }

    public PingIngestService(int shardCount, int queueCapacity, Clock clock, Duration maxAge,
                             Duration futureSkew, BiConsumer<UUID, Ping> processor,
                             BiFunction<UUID, UUID, Long> durableAckLookup) {
        this(shardCount, queueCapacity, clock, maxAge, futureSkew, processor, durableAckLookup, null);
    }

    @SuppressWarnings("unchecked")
    public PingIngestService(int shardCount, int queueCapacity, Clock clock, Duration maxAge,
                             Duration futureSkew, BiConsumer<UUID, Ping> processor,
                             BiFunction<UUID, UUID, Long> durableAckLookup, PipelineMetrics metrics) {
        if (shardCount < 1 || queueCapacity < 1 || clock == null || maxAge == null || maxAge.isNegative()
                || futureSkew == null || futureSkew.isNegative() || processor == null || durableAckLookup == null)
            throw new IllegalArgumentException("Invalid ping ingest configuration");
        this.clock = clock;
        this.maxAge = maxAge;
        this.futureSkew = futureSkew;
        this.processor = processor;
        this.durableAckLookup = durableAckLookup;
        this.metrics = metrics;
        capacity = new BackpressureGuard(queueCapacity);
        queues = (ArrayBlockingQueue<Envelope>[]) new ArrayBlockingQueue<?>[shardCount];
        workers = new Thread[shardCount];
        for (int i = 0; i < shardCount; i++) {
            queues[i] = new ArrayBlockingQueue<>(queueCapacity);
            int shard = i;
            workers[i] = Thread.ofPlatform().name("trip-shard-" + shard).unstarted(() -> consume(shard));
            workers[i].start();
        }
    }

    public synchronized Admission ingest(UUID tenantId, UUID tripId, List<Ping> pings) {
        if (!accepting) throw new IllegalStateException("Ping admission is closed", workerFailure.get());
        if (tenantId == null || tripId == null || pings == null || pings.size() > 500)
            throw new IllegalArgumentException("Malformed ping request");
        List<ItemResult> results = new ArrayList<>(pings.size());
        List<Envelope> valid = new ArrayList<>(pings.size());
        var now = clock.instant();
        long durableFrontier = durableAckLookup.apply(tenantId, tripId);
        for (Ping ping : pings) {
            if (ping == null || !tripId.equals(ping.tripId())) {
                if (metrics != null) metrics.invalid();
                results.add(new ItemResult(ping == null ? -1 : ping.seq(), Status.REJECTED_INVALID));
            } else if (validateTime(ping, now) == Status.REJECTED_CLOCK_SKEW) {
                if (metrics != null) metrics.clockSkew();
                results.add(new ItemResult(ping.seq(), Status.REJECTED_CLOCK_SKEW));
            } else {
                valid.add(new Envelope(tenantId, ping, null, null));
                results.add(new ItemResult(ping.seq(), Status.ACCEPTED));
            }
        }
        if (valid.isEmpty()) return new Admission(results, durableFrontier);
        BackpressureGuard.Reservation reservation = capacity.reserve(valid.size());
        if (reservation == null) {
            if (metrics != null) metrics.backpressure();
            throw new BackpressureException();
        }
        if (metrics != null) metrics.queued(valid.size());
        try {
            for (Envelope envelope : valid) {
                int shard = Math.floorMod(31 * tenantId.hashCode() + tripId.hashCode(), queues.length);
                if (!queues[shard].offer(new Envelope(envelope.tenantId(), envelope.ping(), reservation, null)))
                    throw new IllegalStateException("Reserved shard queue capacity was unavailable");
            }
            if (metrics != null) metrics.admitted(valid.size());
        } catch (RuntimeException failure) {
            reservation.close();
            if (metrics != null) metrics.queued(-valid.size());
            throw failure;
        }
        return new Admission(results, durableFrontier);
    }

    /** Read-only timestamp check used before sequence reservation by the HTTP admission layer. */
    public Status validateTime(Ping ping) {
        if (ping == null) return Status.REJECTED_INVALID;
        return validateTime(ping, clock.instant());
    }

    private Status validateTime(Ping ping, java.time.Instant now) {
        return ping.eventTime().isBefore(now.minus(maxAge)) || ping.eventTime().isAfter(now.plus(futureSkew))
                ? Status.REJECTED_CLOCK_SKEW : Status.ACCEPTED;
    }

    /** Waits until all previously admitted pings for this trip have left its ordered shard. */
    public void flushTrip(UUID tenantId, UUID tripId, Duration timeout) {
        if (tenantId == null || tripId == null || timeout == null || timeout.isNegative() || timeout.isZero())
            throw new IllegalArgumentException("Invalid trip flush request");
        int shard = Math.floorMod(31 * tenantId.hashCode() + tripId.hashCode(), queues.length);
        CompletableFuture<Void> barrier = new CompletableFuture<>();
        try {
            if (!queues[shard].offer(new Envelope(tenantId, null, null, barrier), timeout.toNanos(), TimeUnit.NANOSECONDS))
                throw new BackpressureException();
            barrier.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Trip drain was interrupted", interrupted);
        } catch (java.util.concurrent.TimeoutException timeoutFailure) {
            throw new BackpressureException();
        } catch (ExecutionException failure) {
            throw new IllegalStateException("Trip drain failed", failure.getCause());
        }
    }

    private void consume(int shard) {
        while (accepting || !queues[shard].isEmpty()) {
            try {
                Envelope envelope = queues[shard].poll(100, TimeUnit.MILLISECONDS);
                if (envelope == null) continue;
                if (envelope.barrier() != null) { envelope.barrier().complete(null); continue; }
                if (metrics != null) metrics.queued(-1);
                try { processor.accept(envelope.tenantId(), envelope.ping()); }
                catch (RuntimeException failure) {
                    workerFailures.incrementAndGet();
                    if (metrics != null) { metrics.workerFailure(); metrics.queued(1); }
                    workerFailure.compareAndSet(null, failure);
                    accepting = false;
                    queues[shard].offer(envelope);
                    break;
                }
                // Every admitted request reservation covers N pings. Release one permit per task.
                envelope.reservation().releaseOne();
            } catch (InterruptedException interrupted) {
                // Clearing the interrupt lets the worker drain queued work during graceful close.
            }
        }
    }

    public long workerFailures() { return workerFailures.get(); }
    public int queuedCount() { int count = 0; for (var queue : queues) count += queue.size(); return count; }

    @Override public synchronized void close() {
        accepting = false;
        boolean interrupted = false;
        for (Thread worker : workers) {
            boolean joined = false;
            while (!joined) {
                try { worker.join(); joined = true; }
                catch (InterruptedException ignored) { interrupted = true; }
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
        for (var queue : queues) {
            Envelope pending;
            while ((pending = queue.poll()) != null) {
                if (pending.reservation() != null) pending.reservation().releaseOne();
                if (metrics != null) metrics.queued(-1);
            }
        }
    }

    public static final class BackpressureException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public BackpressureException() { super("Ping admission capacity is full"); }
    }
}
