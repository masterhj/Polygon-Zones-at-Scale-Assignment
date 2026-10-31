package com.polyzones.service.persistence.repository;

import com.polyzones.service.persistence.entity.TenantZoneId;
import com.polyzones.service.persistence.entity.ZoneEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.RepositoryDefinition;

@RepositoryDefinition(domainClass = ZoneEntity.class, idClass = TenantZoneId.class)
public interface ZoneJpaRepository { Optional<ZoneEntity> findByTenantIdAndZoneId(UUID tenantId, UUID zoneId); }
