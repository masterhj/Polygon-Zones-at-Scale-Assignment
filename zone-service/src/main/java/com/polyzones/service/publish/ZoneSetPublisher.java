package com.polyzones.service.publish;

import com.polyzones.core.index.ZoneSnapshot;
import com.polyzones.core.shape.ValidationIssue;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Consumer;

/** Serializes each tenant's DB-first publication through its local atomic snapshot swap. */
public final class ZoneSetPublisher implements AutoCloseable {
    private final ZoneSetStore store;
    private final SnapshotLoader loader;
    private final SnapshotRegistry registry;
    private final Function<ZoneEditCommand, List<ValidationIssue>> validator;
    private final Predicate<ZoneEditCommand> buildFailureInjector;
    private final ConcurrentHashMap<UUID, Lane> lanes = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor builder;
    private final Timer buildTimer;
    private final io.micrometer.core.instrument.Counter swaps;
    private final io.micrometer.core.instrument.Counter failures;
    private final com.polyzones.service.observability.ZoneVersionGaugeRegistry versionGauges;
    private final Consumer<Throwable> postCommitFailureHandler;

    public ZoneSetPublisher(ZoneSetStore store, SnapshotLoader loader, SnapshotRegistry registry,
                            Function<ZoneEditCommand, List<ValidationIssue>> validator,
                            Predicate<ZoneEditCommand> buildFailureInjector,
                            MeterRegistry meters, int builderThreads, int queueCapacity,
                            com.polyzones.service.observability.ZoneVersionGaugeRegistry versionGauges,
                            Consumer<Throwable> postCommitFailureHandler) {
        if (store == null || loader == null || registry == null || validator == null || meters == null
                || builderThreads < 1 || queueCapacity < 1 || versionGauges == null) throw new IllegalArgumentException("Invalid publisher configuration");
        this.store = store;
        this.loader = loader;
        this.registry = registry;
        this.validator = validator;
        this.versionGauges = versionGauges;
        this.postCommitFailureHandler = postCommitFailureHandler == null ? ignored -> { } : postCommitFailureHandler;
        this.buildFailureInjector = buildFailureInjector == null ? ignored -> false : buildFailureInjector;
        this.builder = new ThreadPoolExecutor(builderThreads, builderThreads, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), runnable -> {
                    Thread thread = new Thread(runnable, "zone-snapshot-builder");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        this.buildTimer = meters.timer("index_build_seconds");
        this.swaps = meters.counter("index_swap_total");
        this.failures = meters.counter("index_swap_failures_total");
    }

    public PublishResult publish(UUID tenantId, ZoneEditCommand command, Duration timeout) {
        if (tenantId == null || command == null || timeout == null || timeout.isNegative() || timeout.isZero())
            throw new IllegalArgumentException("Invalid publication request");
        List<ValidationIssue> issues = validator.apply(command);
        if (!issues.isEmpty()) throw new ZoneEditValidationException(issues);
        if (command.idempotencyKey() != null) {
            PublishResult prior = findPrior(tenantId, command);
            if (prior != null) return prior;
        }
        Lane lane = lanes.compute(tenantId, (ignored, current) -> {
            Lane selected = current == null ? new Lane() : current;
            selected.references++;
            return selected;
        });
        boolean locked = false;
        long version = -1;
        boolean committed = false;
        long started = System.nanoTime();
        long timeoutNanos = timeout.toNanos();
        try {
            locked = lane.lock.tryLock(timeoutNanos, TimeUnit.NANOSECONDS);
            if (!locked) throw new PublicationTimeoutException();
            if (command.idempotencyKey() != null) {
                PublishResult prior = findPrior(tenantId, command);
                if (prior != null) return prior;
            }
            version = store.beginBuild(tenantId, command);
            long candidateVersion = version;
            Future<ZoneSnapshot> future = builder.submit(() -> buildTimer.record(() -> {
                if (buildFailureInjector.test(command)) throw new IllegalStateException("Injected snapshot build failure");
                ZoneSnapshot snapshot = loader.loadCandidate(tenantId, candidateVersion);
                if (snapshot == null || !tenantId.equals(snapshot.tenantId()) || snapshot.setVersion() != candidateVersion)
                    throw new IllegalStateException("Candidate snapshot does not match its publication");
                return snapshot;
            }));
            ZoneSnapshot candidate;
            long remainingNanos = timeoutNanos - (System.nanoTime() - started);
            if (remainingNanos <= 0) { future.cancel(true); throw new PublicationTimeoutException(); }
            try { candidate = future.get(remainingNanos, TimeUnit.NANOSECONDS); }
            catch (TimeoutException timedOut) { future.cancel(true); throw new PublicationTimeoutException(timedOut); }
            store.markPublished(tenantId, version);
            committed = true;
            if (!registry.publish(tenantId, candidate)) throw new IllegalStateException("Published snapshot version was not monotonic");
            registerVersionGauge(tenantId);
            swaps.increment();
            return new PublishResult(tenantId, version, candidate);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            failBeforeCommit(tenantId, version, committed, interrupted);
            throw new PublishFailedException(interrupted);
        } catch (ExecutionException | RuntimeException failure) {
            Throwable cause = failure instanceof ExecutionException && failure.getCause() != null ? failure.getCause() : failure;
            failBeforeCommit(tenantId, version, committed, cause);
            if (cause instanceof PublicationTimeoutException timeoutFailure) throw timeoutFailure;
            if (committed) {
                try { postCommitFailureHandler.accept(cause); }
                catch (RuntimeException handlerFailure) { cause.addSuppressed(handlerFailure); }
            }
            throw new PublishFailedException(cause);
        } finally {
            if (locked) lane.lock.unlock();
            lanes.compute(tenantId, (ignored, current) -> {
                if (current != lane) return current;
                lane.references--;
                return lane.references == 0 ? null : lane;
            });
        }
    }

    private PublishResult findPrior(UUID tenantId, ZoneEditCommand command) {
        PublishResult prior = store.findIdempotent(tenantId, command.idempotencyKey(), command.bodyHash());
        if (prior == null) return null;
        try {
            ZoneSnapshot current = registry.latest(tenantId);
            if (current.setVersion() < prior.setVersion()) registry.publish(tenantId, prior.snapshot());
        } catch (IllegalStateException noCurrentSnapshot) {
            registry.publish(tenantId, prior.snapshot());
        }
        registerVersionGauge(tenantId);
        return prior;
    }

    private void registerVersionGauge(UUID tenantId) {
        versionGauges.register(tenantId, registry);
    }

    private void failBeforeCommit(UUID tenantId, long version, boolean committed, Throwable cause) {
        if (version < 0 || committed) return;
        try { store.markFailed(tenantId, version); }
        catch (RuntimeException failure) { cause.addSuppressed(failure); }
        failures.increment();
    }

    @Override public void close() { builder.shutdownNow(); }

    public record PublishResult(UUID tenantId, long setVersion, ZoneSnapshot snapshot) { }
    private static final class Lane { private final ReentrantLock lock = new ReentrantLock(); private int references; }
    public static final class PublishFailedException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public PublishFailedException(Throwable cause) { super("Zone publication failed", cause); }
    }
    public static final class PublicationTimeoutException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        PublicationTimeoutException() { super("Zone publication timed out"); }
        PublicationTimeoutException(Throwable cause) { super("Zone publication timed out", cause); }
    }
    public static final class ZoneEditValidationException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final List<ValidationIssue> issues;
        ZoneEditValidationException(List<ValidationIssue> issues) { super("Zone edit validation failed"); this.issues = List.copyOf(issues); }
        public List<ValidationIssue> issues() { return issues; }
    }
}
