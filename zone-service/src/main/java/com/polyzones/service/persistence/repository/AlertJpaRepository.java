package com.polyzones.service.persistence.repository;

import com.polyzones.service.persistence.entity.AlertEntity;
import com.polyzones.service.persistence.entity.TenantAlertId;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.RepositoryDefinition;

@RepositoryDefinition(domainClass = AlertEntity.class, idClass = TenantAlertId.class)
public interface AlertJpaRepository { Optional<AlertEntity> findByTenantIdAndAlertId(UUID tenantId, UUID alertId); }
