package com.polyzones.service.persistence.entity;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

public class TenantUuidId implements Serializable {
    public UUID tenantId; public UUID id;
    public TenantUuidId() { }
    @Override public boolean equals(Object other) { return other instanceof TenantUuidId key && Objects.equals(tenantId, key.tenantId) && Objects.equals(id, key.id); }
    @Override public int hashCode() { return Objects.hash(tenantId, id); }
}
