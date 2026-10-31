package com.polyzones.service.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class RequestCorrelationFilter extends OncePerRequestFilter {
    public static final String ATTRIBUTE = RequestCorrelationFilter.class.getName() + ".correlationId";
    public static final String HEADER = "X-Request-Id";
    private static final Pattern ACCEPTED = Pattern.compile("^[A-Za-z0-9._-]{8,64}$");

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        String id = incoming != null && ACCEPTED.matcher(incoming).matches() ? incoming : UUID.randomUUID().toString();
        request.setAttribute(ATTRIBUTE, id);
        response.setHeader(HEADER, id);
        MDC.put("correlationId", id);
        try { chain.doFilter(request, response); }
        finally { MDC.remove("correlationId"); }
    }
}
