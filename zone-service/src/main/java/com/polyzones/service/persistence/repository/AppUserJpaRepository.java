package com.polyzones.service.persistence.repository;

import com.polyzones.service.persistence.entity.AppUserEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.RepositoryDefinition;

@RepositoryDefinition(domainClass = AppUserEntity.class, idClass = UUID.class)
public interface AppUserJpaRepository { Optional<AppUserEntity> findByTenantIdAndUsername(UUID tenantId, String username); }
