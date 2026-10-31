package com.polyzones.service.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity(name = "ZoneVersionEntity") @Table(name = "zone_versions") @IdClass(TenantZoneVersionId.class)
public class ZoneVersionEntity {
    @Id @Column(name="tenant_id", nullable=false) private UUID tenantId;
    @Id @Column(name="zone_id", nullable=false) private UUID zoneId;
    @Id @Column(name="version", nullable=false) private Integer version;
    @Column(name="set_version", nullable=false) private Long setVersion;
    @Column(name="name", nullable=false) private String name;
    @Column(name="zone_type", nullable=false) private String zoneType;
    @Column(name="priority", nullable=false) private Integer priority;
    @Column(name="rate_per_km") private BigDecimal ratePerKm;
    @Column(name="surcharge") private BigDecimal surcharge;
    @Column(name="geometry_geojson") private String geometryGeojson;
    @Column(name="vertex_count", nullable=false) private Integer vertexCount;
    @Column(name="min_lon") private Double minLon;
    @Column(name="min_lat") private Double minLat;
    @Column(name="max_lon") private Double maxLon;
    @Column(name="max_lat") private Double maxLat;
    @Column(name="content_hash") private String contentHash;
    @Column(name="deleted", nullable=false) private boolean deleted;
    @Column(name="created_by") private UUID createdBy;
    @Column(name="created_at", nullable=false) private Instant createdAt;
    protected ZoneVersionEntity() { }
}
