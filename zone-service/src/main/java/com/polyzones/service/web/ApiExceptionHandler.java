package com.polyzones.service.web;

import com.polyzones.service.pipeline.PingIngestService;
import com.polyzones.service.pipeline.TripProcessor;
import com.polyzones.service.publish.PinnedSnapshotRegistry;
import com.polyzones.service.application.ApplicationException;
import com.polyzones.service.application.AuthService;
import java.util.List;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Uniform safe RFC 9457 error responses. Internal exception text is never returned. */
@RestControllerAdvice
public final class ApiExceptionHandler {
    @ExceptionHandler(ApiProblemException.class)
    ResponseEntity<ProblemDetail> known(ApiProblemException failure, HttpServletRequest request) {
        return response(failure.status(), failure.code(), failure.getMessage(), request);
    }

    @ExceptionHandler(ApplicationException.class)
    ResponseEntity<ProblemDetail> application(ApplicationException failure, HttpServletRequest request) {
        HttpStatus status = switch (failure.code()) {
            case "ZONE_NOT_FOUND", "TRIP_NOT_FOUND", "VERSION_NOT_FOUND", "EMPLOYEE_NOT_FOUND", "NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "IDEMPOTENCY_CONFLICT", "CONFLICT", "TRIP_COMPLETED" -> HttpStatus.CONFLICT;
            case "FORBIDDEN" -> HttpStatus.FORBIDDEN;
            case "INVALID_CREDENTIALS" -> HttpStatus.UNAUTHORIZED;
            case "PUBLISH_FAILED" -> HttpStatus.INTERNAL_SERVER_ERROR;
            case "ZONE_SET_UNAVAILABLE" -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.BAD_REQUEST;
        };
        return response(status, failure.code(), failure.getMessage(), request);
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class,
            HttpMessageNotReadableException.class, IllegalArgumentException.class})
    ResponseEntity<ProblemDetail> invalid(Exception failure, HttpServletRequest request) {
        if (causedByPayloadLimit(failure)) return response(HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE", "The request body exceeds the configured size limit.", request);
        ProblemDetail body = problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "The request is invalid.", request);
        if (failure instanceof MethodArgumentNotValidException invalid) {
            List<ProblemDetailIssue> issues = invalid.getBindingResult().getFieldErrors().stream()
                    .map(error -> new ProblemDetailIssue("INVALID", error.getField(), "The field is invalid."))
                    .distinct().toList();
            body.setProperty("issues", issues);
        } else if (failure instanceof ConstraintViolationException invalid) {
            List<ProblemDetailIssue> issues = invalid.getConstraintViolations().stream()
                    .map(error -> new ProblemDetailIssue("INVALID", error.getPropertyPath().toString(), "The field is invalid."))
                    .distinct().toList();
            body.setProperty("issues", issues);
        }
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(PayloadTooLargeException.class)
    ResponseEntity<ProblemDetail> payloadTooLarge(PayloadTooLargeException failure, HttpServletRequest request) {
        return response(HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE", "The request body exceeds the configured size limit.", request);
    }

    @ExceptionHandler(AuthService.LoginRateLimitException.class)
    ResponseEntity<ProblemDetail> loginRateLimit(AuthService.LoginRateLimitException failure, HttpServletRequest request) {
        return response(HttpStatus.TOO_MANY_REQUESTS, "LOGIN_THROTTLED", "Too many login attempts. Try again later.", request);
    }

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    ResponseEntity<ProblemDetail> forbidden(org.springframework.security.access.AccessDeniedException failure, HttpServletRequest request) {
        return response(HttpStatus.FORBIDDEN, "FORBIDDEN", "You are not permitted to perform this operation.", request);
    }

    private static boolean causedByPayloadLimit(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) if (cause instanceof PayloadTooLargeException) return true;
        return false;
    }

    @ExceptionHandler({PingIngestService.BackpressureException.class, TripProcessor.CheckpointBacklogException.class})
    ResponseEntity<ProblemDetail> backpressure(Exception failure, HttpServletRequest request) {
        ProblemDetail body = problem(HttpStatus.TOO_MANY_REQUESTS, "BACKPRESSURE", "Ping capacity is temporarily full.", request);
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).header(HttpHeaders.RETRY_AFTER, "1").body(body);
    }

    @ExceptionHandler({PinnedSnapshotRegistry.VersionNotFoundException.class, ResourceNotFoundException.class})
    ResponseEntity<ProblemDetail> notFound(Exception failure, HttpServletRequest request) {
        String code = failure instanceof PinnedSnapshotRegistry.VersionNotFoundException ? "VERSION_NOT_FOUND" : "NOT_FOUND";
        return response(HttpStatus.NOT_FOUND, code, "The requested resource was not found.", request);
    }

    @ExceptionHandler({DataAccessResourceFailureException.class, TransientDataAccessException.class})
    ResponseEntity<ProblemDetail> databaseUnavailable(Exception failure, HttpServletRequest request) {
        return response(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "The service is temporarily unavailable.", request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception failure, HttpServletRequest request) {
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An unexpected error occurred.", request);
    }

    private static ResponseEntity<ProblemDetail> response(HttpStatus status, String code, String detail, HttpServletRequest request) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status);
        if (status == HttpStatus.UNAUTHORIZED) builder.header(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        if (status == HttpStatus.TOO_MANY_REQUESTS) builder.header(HttpHeaders.RETRY_AFTER, "300");
        return builder.body(problem(status, code, detail, request));
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail, HttpServletRequest request) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setType(java.net.URI.create("urn:polyzones:problem:" + code.toLowerCase(java.util.Locale.ROOT)));
        body.setTitle(title(status));
        body.setProperty("code", code);
        Object correlation = request.getAttribute(RequestCorrelationFilter.ATTRIBUTE);
        body.setProperty("correlationId", correlation instanceof String id ? id : "unknown");
        return body;
    }

    private static String title(HttpStatus status) {
        return switch (status) {
            case BAD_REQUEST -> "Bad Request";
            case UNAUTHORIZED -> "Unauthorized";
            case FORBIDDEN -> "Forbidden";
            case CONFLICT -> "Conflict";
            case NOT_FOUND -> "Not Found";
            case TOO_MANY_REQUESTS -> "Too Many Requests";
            case PAYLOAD_TOO_LARGE -> "Payload Too Large";
            case SERVICE_UNAVAILABLE -> "Service Unavailable";
            default -> "Internal Server Error";
        };
    }

    public record ProblemDetailIssue(String code, String field, String message) { }
}
