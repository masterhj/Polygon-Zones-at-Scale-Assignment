package com.polyzones.service.persistence.entity;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

public class TenantZoneVersionId implements Serializable {
    public UUID tenantId; public UUID zoneId; public Integer version;
    public TenantZoneVersionId() { }
    @Override public boolean equals(Object other) { return other instanceof TenantZoneVersionId id && Objects.equals(tenantId, id.tenantId) && Objects.equals(zoneId, id.zoneId) && Objects.equals(version, id.version); }
    @Override public int hashCode() { return Objects.hash(tenantId, zoneId, version); }
}
