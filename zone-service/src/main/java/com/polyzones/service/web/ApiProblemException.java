package com.polyzones.service.web;

import org.springframework.http.HttpStatus;

/** Curated API failure; its message is safe for a client response. */
public class ApiProblemException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final HttpStatus status;
    private final String code;

    public ApiProblemException(HttpStatus status, String code, String safeDetail) {
        super(safeDetail);
        if (status == null || code == null || code.isBlank() || safeDetail == null || safeDetail.isBlank())
            throw new IllegalArgumentException("API problem fields are required");
        this.status = status;
        this.code = code;
    }
    public HttpStatus status() { return status; }
    public String code() { return code; }
}
