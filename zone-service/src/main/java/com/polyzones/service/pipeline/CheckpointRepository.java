package com.polyzones.service.pipeline;

import com.polyzones.core.trip.TripStateCheckpoint;
import java.util.UUID;

/** Implementations must atomically persist the checkpoint, cumulative totals, events and alerts. */
@FunctionalInterface
public interface CheckpointRepository {
    void commit(CheckpointSnapshot snapshot) throws Exception;

    /** Returns the last committed checkpoint, or null for a trip with no committed pings yet. */
    default TripStateCheckpoint loadState(UUID tenantId, UUID tripId) throws Exception {
        throw new UnsupportedOperationException("Checkpoint recovery is not available");
    }
}
