package com.polyzones.service.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Uses the same safe Problem Detail fields for security failures that occur before MVC. */
@Component
public final class SecurityProblemHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {
    private final JsonMapper json;
    public SecurityProblemHandlers(JsonMapper json) { this.json = json; }

    @Override public void commence(HttpServletRequest request, HttpServletResponse response,
                                   AuthenticationException failure) throws IOException {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        write(request, response, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authentication is required.");
    }

    @Override public void handle(HttpServletRequest request, HttpServletResponse response,
                                 AccessDeniedException failure) throws IOException {
        write(request, response, HttpStatus.FORBIDDEN, "FORBIDDEN", "You are not permitted to perform this operation.");
    }

    private void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status,
                       String code, String detail) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(java.net.URI.create("urn:polyzones:problem:" + code.toLowerCase(java.util.Locale.ROOT)));
        problem.setTitle(status == HttpStatus.UNAUTHORIZED ? "Unauthorized" : "Forbidden");
        problem.setProperty("code", code);
        Object correlationId = request.getAttribute(com.polyzones.service.web.RequestCorrelationFilter.ATTRIBUTE);
        problem.setProperty("correlationId", correlationId instanceof String id ? id : "unknown");
        response.setStatus(status.value());
        response.setContentType("application/problem+json");
        response.setCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8.name());
        json.writeValue(response.getOutputStream(), problem);
    }
}
