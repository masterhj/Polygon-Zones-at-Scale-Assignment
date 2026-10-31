package com.polyzones.service.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.polyzones.service.application.ApplicationException;
import com.polyzones.service.pipeline.PingIngestService;
import jakarta.validation.ConstraintViolationException;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.mock.web.MockHttpServletRequest;

/** Checks the shared safe RFC 9457 envelope across the Phase 6 error categories. */
class ApiErrorShapeTest {
    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test void everyPhaseSixErrorCategoryUsesTheSameSafeEnvelope() throws Exception {
        var request = new MockHttpServletRequest();
        request.setAttribute(RequestCorrelationFilter.ATTRIBUTE, "req-12345678");
        assertShape(handler.known(new ApiProblemException(HttpStatus.CONFLICT, "CONFLICT", "A conflict occurred."), request), 409);
        assertShape(handler.application(new ApplicationException("TRIP_NOT_FOUND", "The requested trip was not found."), request), 404);
        assertShape(handler.application(new ApplicationException("ZONE_SET_UNAVAILABLE", "The zone service is temporarily unavailable."), request), 503);
        assertShape(handler.invalid(new ConstraintViolationException("password SQL postgres", Set.of()), request), 400);
        assertShape(handler.payloadTooLarge(new PayloadTooLargeException(), request), 413);
        assertShape(handler.backpressure(new PingIngestService.BackpressureException(), request), 429);
        assertShape(handler.notFound(new ResourceNotFoundException(), request), 404);
        assertShape(handler.databaseUnavailable(new DataAccessResourceFailureException("password SQL postgres jdbc"), request), 503);
        assertShape(handler.unexpected(new IllegalStateException("password SQL postgres jdbc"), request), 500);
    }

    @Test void unauthenticatedProblemAdvertisesBearerAuthentication() throws Exception {
        var request = new MockHttpServletRequest();
        request.setAttribute(RequestCorrelationFilter.ATTRIBUTE, "req-12345678");
        var response = handler.known(new ApiProblemException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authentication is required."), request);
        assertShape(response, 401);
        assertThat(response.getHeaders().getFirst("WWW-Authenticate")).isEqualTo("Bearer");
    }

    private static void assertShape(org.springframework.http.ResponseEntity<ProblemDetail> response, int expectedStatus) throws Exception {
        ProblemDetail body = response.getBody();
        assertThat(response.getStatusCode().value()).isEqualTo(expectedStatus);
        assertThat(body).isNotNull();
        assertThat(body.getType()).isNotNull();
        assertThat(body.getTitle()).isNotBlank();
        assertThat(body.getStatus()).isEqualTo(expectedStatus);
        assertThat(body.getDetail()).isNotBlank();
        assertThat(body.getProperties()).containsKeys("code", "correlationId");
        assertThat(body.getProperties().get("correlationId")).isEqualTo("req-12345678");
        String json = tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(body);
        assertThat(json).doesNotContain("Exception", "at com.", "SQL", "org.hibernate", "postgres", "jdbc", "password");
    }
}
