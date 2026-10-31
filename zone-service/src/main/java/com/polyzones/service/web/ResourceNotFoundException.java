package com.polyzones.service.web;

public final class ResourceNotFoundException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public ResourceNotFoundException() { super("Resource not found"); }
}
