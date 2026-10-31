package com.polyzones.service.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestCorrelationFilterTest {
    private final RequestCorrelationFilter filter = new RequestCorrelationFilter();

    @Test void acceptsOnlyTheCanonicalRequestIdPatternAndAlwaysReturnsTheChosenId() throws Exception {
        MockHttpServletRequest accepted = new MockHttpServletRequest();
        accepted.addHeader("X-Request-Id", "demo.req-1234");
        MockHttpServletResponse acceptedResponse = new MockHttpServletResponse();
        filter.doFilter(accepted, acceptedResponse, (request, response) -> { });
        assertThat(acceptedResponse.getHeader("X-Request-Id")).isEqualTo("demo.req-1234");
        assertThat(accepted.getAttribute(RequestCorrelationFilter.ATTRIBUTE)).isEqualTo("demo.req-1234");

        MockHttpServletRequest invalid = new MockHttpServletRequest();
        invalid.addHeader("X-Request-Id", "short / untrusted");
        MockHttpServletResponse invalidResponse = new MockHttpServletResponse();
        filter.doFilter(invalid, invalidResponse, (request, response) -> { });
        String generated = invalidResponse.getHeader("X-Request-Id");
        assertThat(generated).matches("^[A-Za-z0-9._-]{8,64}$").isNotEqualTo("short / untrusted");
        assertThat(invalid.getAttribute(RequestCorrelationFilter.ATTRIBUTE)).isEqualTo(generated);
    }
}
