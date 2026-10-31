package com.polyzones.service.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/** Adds only the validated opaque tenant UUID to request logging context and always clears it. */
public final class TenantContextFilter extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwt) {
            String tenantId = jwt.getToken().getClaimAsString("tid");
            try {
                UUID tenant = UUID.fromString(tenantId);
                TenantContext.bind(tenant);
                MDC.put("tenantId", tenant.toString());
            } catch (RuntimeException invalidTenant) {
                TenantContext.clear();
            }
        }
        try { chain.doFilter(request, response); }
        finally { TenantContext.clear(); MDC.remove("tenantId"); }
    }
}
