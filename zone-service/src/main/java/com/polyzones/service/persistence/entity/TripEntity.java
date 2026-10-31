package com.polyzones.service.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity(name = "TripEntity") @Table(name = "trips") @IdClass(TenantTripId.class)
public class TripEntity {
    @Id @Column(name="tenant_id", nullable=false) private UUID tenantId;
    @Id @Column(name="trip_id", nullable=false) private UUID tripId;
    @Column(name="employee_id", nullable=false) private UUID employeeId;
    @Column(name="pinned_set_version", nullable=false) private Long pinnedSetVersion;
    @Column(name="status", nullable=false) private String status;
    @Column(name="started_at", nullable=false) private Instant startedAt;
    @Column(name="completed_at") private Instant completedAt;
    @Column(name="processed_seq", nullable=false) private Long processedSeq;
    @Column(name="durable_acked_seq", nullable=false) private Long durableAckedSeq;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name="checkpoint", columnDefinition="jsonb") private String checkpoint;
    @Column(name="checkpoint_at") private Instant checkpointAt;
    @Column(name="unzoned_meters", nullable=false) private Double unzonedMeters;
    @Column(name="next_transition_ordinal", nullable=false) private Long nextTransitionOrdinal;
    protected TripEntity() { }
}
