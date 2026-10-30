package com.polyzones.core.shape;

public final class InvalidPolygonException extends IllegalArgumentException {
    private final ValidationResult result;
    public InvalidPolygonException(ValidationResult result) { super("Polygon validation failed: " + result.issues().size() + " issue(s)"); this.result = result; }
    public ValidationResult result() { return result; }
}
