package com.polyzones.service.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.util.Base64;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/** HS256 signing and strict claim validation. JWT_SECRET is Base64 for at least 32 random bytes. */
@Configuration
public class JwtConfiguration {
    static final String TEST_DEMO_SECRET = "moveinsync-demo-and-test-key-never-use-in-production";

    @Bean SecretKey jwtSigningKey(Environment environment, @Value("${JWT_SECRET:}") String configuredSecret) {
        byte[] secret;
        if (configuredSecret == null || configuredSecret.isBlank()) {
            boolean testOrDemo = environment.acceptsProfiles(org.springframework.core.env.Profiles.of("test", "demo"));
            if (!testOrDemo) throw new IllegalStateException("JWT_SECRET is required and must be Base64 for at least 32 random bytes");
            secret = TEST_DEMO_SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        } else {
            try { secret = Base64.getDecoder().decode(configuredSecret); }
            catch (IllegalArgumentException invalid) {
                throw new IllegalStateException("JWT_SECRET must be valid Base64 containing at least 32 random bytes", invalid);
            }
        }
        if (secret.length < 32) throw new IllegalStateException("JWT_SECRET must contain at least 32 random bytes");
        return new SecretKeySpec(secret, "HmacSHA256");
    }

    @Bean JwtEncoder jwtEncoder(SecretKey jwtSigningKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSigningKey));
    }

    @Bean JwtDecoder jwtDecoder(SecretKey jwtSigningKey, @Value("${security.jwt.issuer:moveinsync-polygon-zones}") String issuer) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSigningKey).macAlgorithm(MacAlgorithm.HS256).build();
        OAuth2TokenValidator<Jwt> requiredClaims = jwt -> {
            boolean validTenant = false;
            try { validTenant = jwt.getClaimAsString("tid") != null && !jwt.getClaimAsString("tid").isBlank()
                    && java.util.UUID.fromString(jwt.getClaimAsString("tid")) != null; }
            catch (RuntimeException ignored) { }
            boolean validSubject = false;
            try { validSubject = java.util.UUID.fromString(jwt.getSubject()) != null; }
            catch (RuntimeException ignored) { }
            Object roleClaim = jwt.getClaims().get("roles");
            boolean validRoles = roleClaim instanceof java.util.List<?> roles && !roles.isEmpty()
                    && roles.stream().allMatch(role -> role instanceof String name
                            && java.util.Set.of("ADMIN", "OPERATOR", "EMPLOYEE").contains(name));
            boolean validJti = jwt.getId() != null && !jwt.getId().isBlank();
            boolean validTimeClaims = jwt.getIssuedAt() != null && jwt.getExpiresAt() != null
                    && jwt.getExpiresAt().isAfter(jwt.getIssuedAt());
            if (validTenant && validSubject && validRoles && validJti && validTimeClaims)
                return OAuth2TokenValidatorResult.success();
            return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Required token claims are invalid.", null));
        };
        decoder.setJwtValidator(new org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer), requiredClaims));
        return decoder;
    }

    @Bean JwtTokenService jwtTokenService(JwtEncoder encoder, java.time.Clock clock,
            @Value("${security.jwt.issuer:moveinsync-polygon-zones}") String issuer,
            @Value("${security.jwt.access-token-lifetime:15m}") java.time.Duration lifetime) {
        return new JwtTokenService(encoder, clock, issuer, lifetime);
    }
}
