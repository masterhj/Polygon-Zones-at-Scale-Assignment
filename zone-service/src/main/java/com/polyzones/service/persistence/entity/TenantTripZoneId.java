package com.polyzones.service.persistence.entity;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

public class TenantTripZoneId implements Serializable {
    public UUID tenantId; public UUID tripId; public UUID zoneId;
    public TenantTripZoneId() { }
    @Override public boolean equals(Object other) { return other instanceof TenantTripZoneId id && Objects.equals(tenantId, id.tenantId) && Objects.equals(tripId, id.tripId) && Objects.equals(zoneId, id.zoneId); }
    @Override public int hashCode() { return Objects.hash(tenantId, tripId, zoneId); }
}
