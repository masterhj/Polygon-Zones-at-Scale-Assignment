package com.polyzones.service.persistence.entity;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

public class TenantVersionId implements Serializable {
    public UUID tenantId; public Long version;
    public TenantVersionId() { }
    public TenantVersionId(UUID tenantId, Long version) { this.tenantId = tenantId; this.version = version; }
    @Override public boolean equals(Object other) { return other instanceof TenantVersionId id && Objects.equals(tenantId, id.tenantId) && Objects.equals(version, id.version); }
    @Override public int hashCode() { return Objects.hash(tenantId, version); }
}
