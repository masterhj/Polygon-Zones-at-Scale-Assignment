package com.polyzones.service.persistence.repository;

import com.polyzones.service.persistence.entity.TenantVersionId;
import com.polyzones.service.persistence.entity.ZoneSetVersionEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.RepositoryDefinition;

@RepositoryDefinition(domainClass = ZoneSetVersionEntity.class, idClass = TenantVersionId.class)
public interface ZoneSetVersionJpaRepository { Optional<ZoneSetVersionEntity> findByTenantIdAndVersion(UUID tenantId, Long version); }
