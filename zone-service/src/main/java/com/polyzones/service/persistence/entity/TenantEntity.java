package com.polyzones.service.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;

@Entity(name = "TenantEntity") @Table(name = "tenants")
public class TenantEntity {
    @Id @Column(name="id", nullable=false) private UUID id;
    @Column(name="name", nullable=false) private String name;
    @Column(name="timezone", nullable=false) private String timezone;
    @Column(name="night_start", nullable=false) private LocalTime nightStart;
    @Column(name="night_end", nullable=false) private LocalTime nightEnd;
    @Column(name="created_at", nullable=false) private Instant createdAt;
    protected TenantEntity() { }
}
