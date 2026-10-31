package com.polyzones.service.persistence.repository;

import com.polyzones.service.persistence.entity.TenantTripId;
import com.polyzones.service.persistence.entity.TripEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.RepositoryDefinition;

@RepositoryDefinition(domainClass = TripEntity.class, idClass = TenantTripId.class)
public interface TripJpaRepository { Optional<TripEntity> findByTenantIdAndTripId(UUID tenantId, UUID tripId); }
