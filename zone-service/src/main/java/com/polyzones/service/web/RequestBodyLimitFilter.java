package com.polyzones.service.web;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Enforces a byte cap for both Content-Length and streamed/chunked JSON requests. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public final class RequestBodyLimitFilter extends OncePerRequestFilter {
    private final long maxBytes;
    public RequestBodyLimitFilter(@Value("${zones.http.max-json-bytes:10485760}") long maxBytes) {
        if (maxBytes < 1) throw new IllegalArgumentException("Request body limit must be positive");
        this.maxBytes = maxBytes;
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > maxBytes) { tooLarge(request, response); return; }
        try { chain.doFilter(new LimitedRequest(request), response); }
        catch (PayloadTooLargeException limit) { if (!response.isCommitted()) tooLarge(request, response); }
    }

    private static void tooLarge(HttpServletRequest request, HttpServletResponse response) throws IOException {
        Object value = request.getAttribute(RequestCorrelationFilter.ATTRIBUTE);
        String id = value instanceof String s ? s : "unknown";
        response.setStatus(413);
        response.setContentType("application/problem+json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"type\":\"urn:polyzones:problem:payload_too_large\",\"title\":\"Payload Too Large\",\"status\":413,\"detail\":\"The request body exceeds the configured size limit.\",\"code\":\"PAYLOAD_TOO_LARGE\",\"correlationId\":\"" + id + "\"}");
    }

    private final class LimitedRequest extends HttpServletRequestWrapper {
        private LimitedInput input;
        private LimitedRequest(HttpServletRequest request) { super(request); }
        @Override public ServletInputStream getInputStream() throws IOException {
            if (input == null) input = new LimitedInput(super.getInputStream());
            return input;
        }
        @Override public BufferedReader getReader() throws IOException {
            return new BufferedReader(new InputStreamReader(getInputStream(), getCharacterEncoding() == null
                    ? StandardCharsets.UTF_8 : java.nio.charset.Charset.forName(getCharacterEncoding())));
        }
    }

    private final class LimitedInput extends ServletInputStream {
        private final ServletInputStream delegate;
        private long read;
        private LimitedInput(ServletInputStream delegate) { this.delegate = delegate; }
        @Override public int read() throws IOException {
            int value = delegate.read();
            if (value >= 0 && ++read > maxBytes) throw new PayloadTooLargeException();
            return value;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            int count = delegate.read(bytes, offset, (int) Math.min(length, maxBytes + 1 - read));
            if (count > 0 && (read += count) > maxBytes) throw new PayloadTooLargeException();
            return count;
        }
        @Override public boolean isFinished() { return delegate.isFinished(); }
        @Override public boolean isReady() { return delegate.isReady(); }
        @Override public void setReadListener(ReadListener listener) { delegate.setReadListener(listener); }
    }
}
