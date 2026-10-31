package com.polyzones.service.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    @Bean TenantContextFilter tenantContextFilter() { return new TenantContextFilter(); }

    @Bean FilterRegistrationBean<TenantContextFilter> tenantContextFilterServletRegistration(TenantContextFilter filter) {
        FilterRegistrationBean<TenantContextFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter roles = new JwtGrantedAuthoritiesConverter();
        roles.setAuthoritiesClaimName("roles");
        roles.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(roles);
        return converter;
    }

    @Bean SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationConverter converter,
                                                   SecurityProblemHandlers problems, TenantContextFilter tenantContextFilter)
            throws Exception {
        http
            // CSRF is disabled because this service has no cookie-backed browser sessions; it accepts bearer tokens only.
            .csrf(csrf -> csrf.disable())
            .cors(cors -> cors.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(errors -> errors.authenticationEntryPoint(problems).accessDeniedHandler(problems))
            .authorizeHttpRequests(auth -> auth
                    .requestMatchers("/api/v1/auth/login", "/actuator/health/**").permitAll()
                    .requestMatchers("/actuator/**").hasRole("ADMIN")
                    .requestMatchers(HttpMethod.POST, "/api/v1/zones/validate", "/api/v1/zones").hasRole("ADMIN")
                    .requestMatchers(HttpMethod.PUT, "/api/v1/zones/**").hasRole("ADMIN")
                    .requestMatchers(HttpMethod.DELETE, "/api/v1/zones/**").hasRole("ADMIN")
                    .requestMatchers(HttpMethod.POST, "/api/v1/zones/query", "/api/v1/zones/query/batch").hasAnyRole("OPERATOR", "ADMIN")
                    .requestMatchers(HttpMethod.GET, "/api/v1/zones/*/versions", "/api/v1/zone-sets").hasAnyRole("OPERATOR", "ADMIN")
                    .requestMatchers("/api/v1/alerts/**").hasAnyRole("OPERATOR", "ADMIN")
                    .requestMatchers(HttpMethod.POST, "/api/v1/trips/*/pings").hasRole("OPERATOR")
                    .requestMatchers(HttpMethod.POST, "/api/v1/trips", "/api/v1/trips/*/complete").hasAnyRole("OPERATOR", "ADMIN")
                    .requestMatchers("/api/v1/trips/**", "/api/v1/zones/**", "/api/v1/zone-sets").authenticated()
                    .anyRequest().denyAll())
            .oauth2ResourceServer(resource -> resource.jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
                    .authenticationEntryPoint(problems).accessDeniedHandler(problems))
            .headers(Customizer.withDefaults());
        http.addFilterAfter(tenantContextFilter, BearerTokenAuthenticationFilter.class);
        return http.build();
    }
}
