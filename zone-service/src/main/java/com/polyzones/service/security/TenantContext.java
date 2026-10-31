package com.polyzones.service.security;

import com.polyzones.service.web.ApiProblemException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** Tenant identity comes only from the authenticated token; controllers never accept a tenant id parameter. */
public final class TenantContext {
    private static final ThreadLocal<UUID> REQUEST_TENANT = new ThreadLocal<>();
    private TenantContext() { }

    public static UUID current() {
        UUID requestTenant = REQUEST_TENANT.get();
        if (requestTenant != null) return requestTenant;
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwt) {
            String tenant = jwt.getToken().getClaimAsString("tid");
            try { if (tenant != null) return UUID.fromString(tenant); }
            catch (IllegalArgumentException ignored) { /* return the same curated authentication error */ }
        }
        throw new ApiProblemException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authentication is required.");
    }

    public static UUID requireTenantId() { return current(); }

    public static UUID currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwt) {
            try { return UUID.fromString(jwt.getToken().getSubject()); }
            catch (IllegalArgumentException ignored) { }
        }
        throw new ApiProblemException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authentication is required.");
    }

    public static boolean hasRole(String role) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_" + role));
    }

    static void bind(UUID tenantId) { REQUEST_TENANT.set(tenantId); }
    static void clear() { REQUEST_TENANT.remove(); }
}
