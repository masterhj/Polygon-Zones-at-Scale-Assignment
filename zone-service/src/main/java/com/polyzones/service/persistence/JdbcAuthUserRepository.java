package com.polyzones.service.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Narrow credential lookup used only by the login flow; no tenant/user name is logged. */
@Repository
public class JdbcAuthUserRepository {
    private final JdbcTemplate jdbc;
    public JdbcAuthUserRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<Credential> findCredential(String tenantName, String username) {
        return jdbc.query("""
                select t.id as tenant_id,u.id as user_id,u.password_hash,u.role,u.enabled
                  from tenants t join app_users u on u.tenant_id=t.id
                 where t.name=? and u.username=?
                """, rs -> rs.next() ? Optional.of(new Credential(rs.getObject("tenant_id", UUID.class),
                rs.getObject("user_id", UUID.class), rs.getString("password_hash"), rs.getString("role"), rs.getBoolean("enabled")))
                : Optional.empty(), tenantName, username);
    }

    public record Credential(UUID tenantId, UUID userId, String passwordHash, String role, boolean enabled) {
        @Override public String toString() {
            return "Credential[tenantId=" + tenantId + ", userId=" + userId + ", passwordHash=<redacted>, role=" + role + ", enabled=" + enabled + "]";
        }
    }
}
