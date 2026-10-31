package com.polyzones.service.persistence.repository;

import com.polyzones.service.persistence.entity.TenantZoneVersionId;
import com.polyzones.service.persistence.entity.ZoneVersionEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.repository.RepositoryDefinition;

@RepositoryDefinition(domainClass = ZoneVersionEntity.class, idClass = TenantZoneVersionId.class)
public interface ZoneVersionJpaRepository { List<ZoneVersionEntity> findByTenantIdAndZoneIdOrderByVersionDesc(UUID tenantId, UUID zoneId); }
