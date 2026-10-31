package com.polyzones.service.application;

/** Transport-neutral application failure with a stable public error code and curated detail. */
public final class ApplicationException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String code;
    public ApplicationException(String code, String safeDetail) { super(safeDetail); this.code = code; }
    public String code() { return code; }
}
