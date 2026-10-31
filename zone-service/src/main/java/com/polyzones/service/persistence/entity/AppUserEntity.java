package com.polyzones.service.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity(name = "AppUserEntity") @Table(name = "app_users")
public class AppUserEntity {
    @Id @Column(name="id", nullable=false) private UUID id;
    @Column(name="tenant_id", nullable=false) private UUID tenantId;
    @Column(name="username", nullable=false) private String username;
    @Column(name="password_hash", nullable=false) private String passwordHash;
    @Column(name="role", nullable=false) private String role;
    @Column(name="enabled", nullable=false) private boolean enabled;
    @Column(name="created_at", nullable=false) private Instant createdAt;
    protected AppUserEntity() { }
}
