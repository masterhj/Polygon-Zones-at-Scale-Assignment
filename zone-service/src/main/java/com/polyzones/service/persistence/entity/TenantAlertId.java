package com.polyzones.service.persistence.entity;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

public class TenantAlertId implements Serializable {
    public UUID tenantId; public UUID alertId;
    public TenantAlertId() { }
    @Override public boolean equals(Object other) { return other instanceof TenantAlertId key && Objects.equals(tenantId, key.tenantId) && Objects.equals(alertId, key.alertId); }
    @Override public int hashCode() { return Objects.hash(tenantId, alertId); }
}
