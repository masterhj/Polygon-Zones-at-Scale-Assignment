package com.polyzones.service.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class TenantContextFilterTest {
    @AfterEach void cleanContext() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
        MDC.clear();
    }

    @Test void bindsJwtTenantForTheRequestAndClearsThreadLocalAndMdcEvenOnFailure() {
        UUID tenant = UUID.randomUUID();
        Jwt jwt = Jwt.withTokenValue("opaque-test-token").header("alg", "none")
                .subject(UUID.randomUUID().toString()).claim("tid", tenant.toString()).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        TenantContextFilter filter = new TenantContextFilter();

        assertThatThrownBy(() -> filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
                (request, response) -> {
                    assertThat(TenantContext.current()).isEqualTo(tenant);
                    assertThat(MDC.get("tenantId")).isEqualTo(tenant.toString());
                    throw new jakarta.servlet.ServletException("synthetic downstream failure");
                })).isInstanceOf(jakarta.servlet.ServletException.class);

        assertThat(MDC.get("tenantId")).isNull();
        SecurityContextHolder.clearContext();
        assertThatThrownBy(TenantContext::current).isInstanceOf(com.polyzones.service.web.ApiProblemException.class);
    }
}
