package com.polyzones.service.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.polyzones.service.persistence.JdbcAuthUserRepository;
import com.polyzones.service.web.dto.LoginRequest;
import com.polyzones.service.web.dto.LoginResponse;
import org.junit.jupiter.api.Test;

class AuthValueRedactionTest {
    @Test void authenticationValuesDoNotExposePiiSecretsOrTokensThroughToString() {
        LoginRequest request = new LoginRequest("acme", "employee@example.test", "a-secret-password");
        LoginResponse response = new LoginResponse("eyJ.secret.signature", 900, "Bearer");
        JdbcAuthUserRepository.Credential credential = new JdbcAuthUserRepository.Credential(
                java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), "{bcrypt}hash-value", "ADMIN", true);

        assertThat(request.toString()).doesNotContain("acme", "employee@example.test", "a-secret-password");
        assertThat(response.toString()).doesNotContain("eyJ.secret.signature");
        assertThat(credential.toString()).doesNotContain("{bcrypt}hash-value");
    }
}
