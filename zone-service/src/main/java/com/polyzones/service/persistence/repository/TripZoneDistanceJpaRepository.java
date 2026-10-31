package com.polyzones.service.persistence.repository;

import com.polyzones.service.persistence.entity.TenantTripZoneId;
import com.polyzones.service.persistence.entity.TripZoneDistanceEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.repository.RepositoryDefinition;

@RepositoryDefinition(domainClass = TripZoneDistanceEntity.class, idClass = TenantTripZoneId.class)
public interface TripZoneDistanceJpaRepository { List<TripZoneDistanceEntity> findByTenantIdAndTripId(UUID tenantId, UUID tripId); }
