package com.polyzones.service.persistence.entity;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

public class TenantTripId implements Serializable {
    public UUID tenantId; public UUID tripId;
    public TenantTripId() { }
    @Override public boolean equals(Object other) { return other instanceof TenantTripId id && Objects.equals(tenantId, id.tenantId) && Objects.equals(tripId, id.tripId); }
    @Override public int hashCode() { return Objects.hash(tenantId, tripId); }
}
