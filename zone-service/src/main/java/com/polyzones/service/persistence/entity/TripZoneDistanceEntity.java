package com.polyzones.service.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity(name = "TripZoneDistanceEntity") @Table(name = "trip_zone_distance") @IdClass(TenantTripZoneId.class)
public class TripZoneDistanceEntity {
    @Id @Column(name="tenant_id", nullable=false) private UUID tenantId;
    @Id @Column(name="trip_id", nullable=false) private UUID tripId;
    @Id @Column(name="zone_id", nullable=false) private UUID zoneId;
    @Column(name="zone_version", nullable=false) private Integer zoneVersion;
    @Column(name="zone_version_set_version", nullable=false) private Long zoneVersionSetVersion;
    @Column(name="pinned_set_version", nullable=false) private Long pinnedSetVersion;
    @Column(name="meters", nullable=false) private Double meters;
    protected TripZoneDistanceEntity() { }
}
