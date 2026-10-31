package com.polyzones.service.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.ServletInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestBodyLimitFilterTest {
    @Test void rejectsKnownOversizedBodyAsProblemJson() throws Exception {
        RequestBodyLimitFilter filter = new RequestBodyLimitFilter(4);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContent("12345".getBytes(StandardCharsets.UTF_8));
        request.setAttribute(RequestCorrelationFilter.ATTRIBUTE, "req-12345678");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (wrapped, ignored) -> { throw new AssertionError("oversized body reached the handler"); });
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat(response.getContentAsString()).contains("PAYLOAD_TOO_LARGE", "req-12345678");
    }

    @Test void enforcesLimitWhileReadingChunkedBody() throws Exception {
        RequestBodyLimitFilter filter = new RequestBodyLimitFilter(4);
        MockHttpServletRequest request = new MockHttpServletRequest() {
            @Override public long getContentLengthLong() { return -1; }
            @Override public int getContentLength() { return -1; }
        };
        request.setContent("12345".getBytes(StandardCharsets.UTF_8));
        request.setAttribute(RequestCorrelationFilter.ATTRIBUTE, "req-12345678");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (wrapped, ignored) -> {
            ServletInputStream input = wrapped.getInputStream();
            while (input.read() >= 0) { /* exercise the capped stream */ }
        });
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("PAYLOAD_TOO_LARGE");
    }
}
