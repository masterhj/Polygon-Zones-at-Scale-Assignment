package com.polyzones.service.persistence.repository;

import com.polyzones.service.persistence.entity.ZoneEventEntity;
import com.polyzones.service.persistence.entity.ZoneEventId;
import java.util.List;
import java.util.UUID;
import org.springframework.data.repository.RepositoryDefinition;

@RepositoryDefinition(domainClass = ZoneEventEntity.class, idClass = ZoneEventId.class)
public interface ZoneEventJpaRepository { List<ZoneEventEntity> findByTenantIdAndTripIdOrderByTransitionOrdinal(UUID tenantId, UUID tripId); }
