package com.polyzones.service.persistence.entity;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

public class TenantZoneId implements Serializable {
    public UUID tenantId; public UUID zoneId;
    public TenantZoneId() { }
    @Override public boolean equals(Object other) { return other instanceof TenantZoneId id && Objects.equals(tenantId, id.tenantId) && Objects.equals(zoneId, id.zoneId); }
    @Override public int hashCode() { return Objects.hash(tenantId, zoneId); }
}
