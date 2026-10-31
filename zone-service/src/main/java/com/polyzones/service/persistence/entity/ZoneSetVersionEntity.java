package com.polyzones.service.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity(name = "ZoneSetVersionEntity") @Table(name = "zone_set_versions") @IdClass(TenantVersionId.class)
public class ZoneSetVersionEntity {
    @Id @Column(name="tenant_id", nullable=false) private UUID tenantId;
    @Id @Column(name="version", nullable=false) private Long version;
    @Column(name="status", nullable=false) private String status;
    @Column(name="created_by") private UUID createdBy;
    @Column(name="created_at", nullable=false) private Instant createdAt;
    @Column(name="published_at") private Instant publishedAt;
    @Column(name="idempotency_key") private String idempotencyKey;
    @Column(name="request_hash") private String requestHash;
    protected ZoneSetVersionEntity() { }
}
