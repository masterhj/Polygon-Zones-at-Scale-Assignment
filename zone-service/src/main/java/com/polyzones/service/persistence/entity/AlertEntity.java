package com.polyzones.service.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity(name = "AlertEntity") @Table(name = "alerts") @IdClass(TenantAlertId.class)
public class AlertEntity {
    @Id @Column(name="tenant_id", nullable=false) private UUID tenantId;
    @Id @Column(name="alert_id", nullable=false) private UUID alertId;
    @Column(name="trip_id", nullable=false) private UUID tripId;
    @Column(name="zone_id", nullable=false) private UUID zoneId;
    @Column(name="source_event_id", nullable=false) private UUID sourceEventId;
    @Column(name="kind", nullable=false) private String kind;
    @Column(name="severity", nullable=false) private String severity;
    @Column(name="event_time", nullable=false) private Instant eventTime;
    @Column(name="zone_version", nullable=false) private Integer zoneVersion;
    @Column(name="zone_version_set_version", nullable=false) private Long zoneVersionSetVersion;
    @Column(name="evaluated_set_version", nullable=false) private Long evaluatedSetVersion;
    @Column(name="created_at", nullable=false) private Instant createdAt;
    @Column(name="acknowledged", nullable=false) private boolean acknowledged;
    @Version @Column(name="row_version", nullable=false) private Long rowVersion;
    protected AlertEntity() { }
}
