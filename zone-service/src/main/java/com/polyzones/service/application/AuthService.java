package com.polyzones.service.application;

import com.polyzones.service.persistence.JdbcAuthUserRepository;
import com.polyzones.service.security.JwtTokenService;
import com.polyzones.service.security.LoginThrottle;
import com.polyzones.service.web.dto.LoginRequest;
import com.polyzones.service.web.dto.LoginResponse;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public final class AuthService {
    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final String FAILURE_MESSAGE = "The tenant, username, or password is invalid.";
    private final JdbcAuthUserRepository users;
    private final PasswordEncoder passwords;
    private final JwtTokenService tokens;
    private final LoginThrottle throttle;
    private final String dummyHash;

    public AuthService(JdbcAuthUserRepository users, PasswordEncoder passwords, JwtTokenService tokens,
                       LoginThrottle throttle) {
        this.users = users; this.passwords = passwords; this.tokens = tokens; this.throttle = throttle;
        this.dummyHash = passwords.encode(UUID.randomUUID().toString());
    }

    public LoginResponse login(LoginRequest request, String remoteAddress) {
        LoginThrottle.Key key = throttle.key(request.tenant(), request.username(), remoteAddress);
        if (throttle.blocked(key)) throw new LoginRateLimitException();
        var credential = users.findCredential(request.tenant(), request.username()).orElse(null);
        boolean passwordMatches;
        try { passwordMatches = passwords.matches(request.password(), credential == null ? dummyHash : credential.passwordHash()); }
        catch (IllegalArgumentException malformedStoredHash) {
            passwords.matches(request.password(), dummyHash);
            passwordMatches = false;
        }
        if (credential == null || !credential.enabled() || !passwordMatches) {
            throttle.failed(key);
            String tenantId = credential == null ? "unknown" : credential.tenantId().toString();
            log.warn("Login rejected tenantId={} usernameHash={}", tenantId, key.usernameHash());
            throw new ApplicationException("INVALID_CREDENTIALS", FAILURE_MESSAGE);
        }
        throttle.succeeded(key);
        JwtTokenService.LoginToken token = tokens.issue(credential.userId(), credential.tenantId(), credential.role());
        return new LoginResponse(token.accessToken(), token.expiresInSeconds(), "Bearer");
    }

    public static final class LoginRateLimitException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public LoginRateLimitException() { super("Login rate limit exceeded"); }
    }
}
