package com.polyzones.service.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class JwtConfigurationTest {
    private final JwtConfiguration configuration = new JwtConfiguration();

    @Test void productionProfileFailsFastWhenSecretIsMissingOrShort() {
        MockEnvironment production = new MockEnvironment().withProperty("spring.profiles.active", "production");
        assertThatThrownBy(() -> configuration.jwtSigningKey(production, ""))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("JWT_SECRET is required");
        assertThatThrownBy(() -> configuration.jwtSigningKey(production, Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("at least 32 random bytes");
    }

    @Test void acceptsAtLeastThirtyTwoConfiguredBytesAndDemoProfileHasDocumentedFallback() {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        var key = configuration.jwtSigningKey(new MockEnvironment().withProperty("spring.profiles.active", "production"),
                Base64.getEncoder().encodeToString(secret));
        assertThat(key.getEncoded()).containsExactly(secret);
        assertThat(configuration.jwtSigningKey(new MockEnvironment().withProperty("spring.profiles.active", "demo"), "")
                .getEncoded()).hasSizeGreaterThanOrEqualTo(32);
    }
}
