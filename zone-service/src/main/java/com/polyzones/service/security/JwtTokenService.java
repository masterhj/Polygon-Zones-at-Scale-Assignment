package com.polyzones.service.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;

public final class JwtTokenService {
    private final JwtEncoder encoder;
    private final Clock clock;
    private final String issuer;
    private final Duration lifetime;

    public JwtTokenService(JwtEncoder encoder, Clock clock, String issuer, Duration lifetime) {
        if (encoder == null || clock == null || issuer == null || issuer.isBlank() || lifetime == null
                || lifetime.isZero() || lifetime.isNegative()) throw new IllegalArgumentException("Invalid JWT service configuration");
        this.encoder = encoder; this.clock = clock; this.issuer = issuer; this.lifetime = lifetime;
    }

    public LoginToken issue(UUID userId, UUID tenantId, String role) {
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(lifetime);
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer(issuer).subject(userId.toString())
                .claim("tid", tenantId.toString()).claim("roles", List.of(role))
                .issuedAt(issuedAt).expiresAt(expiresAt).id(UUID.randomUUID().toString()).build();
        String token = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(), claims)).getTokenValue();
        return new LoginToken(token, lifetime.toSeconds());
    }

    public record LoginToken(String accessToken, long expiresInSeconds) { }
}
