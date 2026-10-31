package com.polyzones.service.persistence.repository;

import com.polyzones.service.persistence.entity.TenantEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.RepositoryDefinition;

@RepositoryDefinition(domainClass = TenantEntity.class, idClass = UUID.class)
public interface TenantJpaRepository {
    Optional<TenantEntity> findById(UUID id);
}
