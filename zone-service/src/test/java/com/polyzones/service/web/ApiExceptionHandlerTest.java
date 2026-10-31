package com.polyzones.service.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.Set;
import org.springframework.dao.DataAccessResourceFailureException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.mock.web.MockHttpServletRequest;

class ApiExceptionHandlerTest {
    @Test void returnsCuratedProblemDetailWithCorrelationId() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(RequestCorrelationFilter.ATTRIBUTE, "req-12345678");
        var response = new ApiExceptionHandler().known(
                new ApiProblemException(HttpStatus.CONFLICT, "CONFLICT", "The update conflicts with current state."), request);
        ProblemDetail body = response.getBody();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(body.getStatus()).isEqualTo(409);
        assertThat(body.getProperties()).containsEntry("code", "CONFLICT").containsEntry("correlationId", "req-12345678");
        assertThat(body.getDetail()).doesNotContain("Exception", "SQL", "jdbc", "postgres");
    }

    @Test void genericErrorsNeverExposeInternalFailureText() {
        HttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(RequestCorrelationFilter.ATTRIBUTE, "req-12345678");
        var response = new ApiExceptionHandler().unexpected(new IllegalStateException("jdbc SQL postgres secret"), request);
        assertThat(response.getBody().getDetail()).isEqualTo("An unexpected error occurred.");
        assertThat(response.getBody().getDetail()).doesNotContain("jdbc", "SQL", "postgres", "Exception");
    }

    @Test void validationBackpressureAndDatabaseErrorsUseStableSafeBodies() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(RequestCorrelationFilter.ATTRIBUTE, "req-12345678");
        ApiExceptionHandler handler = new ApiExceptionHandler();

        var validation = handler.invalid(new ConstraintViolationException("secret SQL", Set.of()), request);
        assertThat(validation.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(validation.getBody().getProperties()).containsEntry("code", "VALIDATION_FAILED").containsKey("issues");
        assertThat(validation.getBody().getDetail()).doesNotContain("secret", "SQL");

        var overload = handler.backpressure(new com.polyzones.service.pipeline.PingIngestService.BackpressureException(), request);
        assertThat(overload.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(overload.getHeaders().getFirst("Retry-After")).isEqualTo("1");
        assertThat(overload.getBody().getProperties()).containsEntry("code", "BACKPRESSURE");

        var unavailable = handler.databaseUnavailable(new DataAccessResourceFailureException("password jdbc postgres"), request);
        assertThat(unavailable.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(unavailable.getBody().getDetail()).isEqualTo("The service is temporarily unavailable.");
        assertThat(unavailable.getBody().getDetail()).doesNotContain("password", "jdbc", "postgres");
    }
}
