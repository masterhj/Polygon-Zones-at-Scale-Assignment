package com.polyzones.service.security;

import com.polyzones.service.web.ApiProblemException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** Tenant identity comes only from the authenticated token; controllers never accept a tenant id parameter. */
public final class TenantContext {
    private TenantContext() { }

    public static UUID requireTenantId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwt) {
            String tenant = jwt.getToken().getClaimAsString("tid");
            try { if (tenant != null) return UUID.fromString(tenant); }
            catch (IllegalArgumentException ignored) { /* return the same curated authentication error */ }
        }
        throw new ApiProblemException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authentication is required.");
    }
}
