package com.polyzones.service.pipeline;

import java.util.concurrent.Semaphore;

/** Reserves request capacity atomically before admission, so requests are never partially accepted. */
public final class BackpressureGuard {
    private final Semaphore slots;
    public BackpressureGuard(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        slots = new Semaphore(capacity, true);
    }
    public Reservation reserve(int itemCount) {
        if (itemCount < 1) throw new IllegalArgumentException("itemCount must be positive");
        return slots.tryAcquire(itemCount) ? new Reservation(slots, itemCount) : null;
    }
    public int availableCapacity() { return slots.availablePermits(); }
    public static final class Reservation implements AutoCloseable {
        private final Semaphore semaphore; private int remaining;
        private Reservation(Semaphore semaphore, int count) { this.semaphore = semaphore; this.remaining = count; }
        public synchronized void releaseOne() {
            if (remaining == 0) throw new IllegalStateException("Reservation is exhausted");
            remaining--;
            semaphore.release();
        }
        @Override public synchronized void close() {
            if (remaining > 0) { semaphore.release(remaining); remaining = 0; }
        }
    }
}
