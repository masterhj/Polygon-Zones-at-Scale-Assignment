package com.polyzones.service.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity(name = "ZoneEntity") @Table(name = "zones") @IdClass(TenantZoneId.class)
public class ZoneEntity {
    @Id @Column(name="tenant_id", nullable=false) private UUID tenantId;
    @Id @Column(name="zone_id", nullable=false) private UUID zoneId;
    @Column(name="created_at", nullable=false) private Instant createdAt;
    protected ZoneEntity() { }
}
