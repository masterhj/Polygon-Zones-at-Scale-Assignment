package com.polyzones.service.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity(name = "ZoneEventEntity") @Table(name = "zone_events") @IdClass(ZoneEventId.class)
public class ZoneEventEntity {
    @Id @Column(name="tenant_id", nullable=false) private UUID tenantId;
    @Id @Column(name="event_id", nullable=false) private UUID eventId;
    @Column(name="trip_id", nullable=false) private UUID tripId;
    @Column(name="zone_id", nullable=false) private UUID zoneId;
    @Column(name="zone_version", nullable=false) private Integer zoneVersion;
    @Column(name="zone_version_set_version", nullable=false) private Long zoneVersionSetVersion;
    @Column(name="evaluated_set_version", nullable=false) private Long evaluatedSetVersion;
    @Column(name="transition_ordinal", nullable=false) private Long transitionOrdinal;
    @Column(name="kind", nullable=false) private String kind;
    @Column(name="event_time", nullable=false) private Instant eventTime;
    @Column(name="dwell_seconds") private Double dwellSeconds;
    @Column(name="reason", nullable=false) private String reason;
    protected ZoneEventEntity() { }
}
