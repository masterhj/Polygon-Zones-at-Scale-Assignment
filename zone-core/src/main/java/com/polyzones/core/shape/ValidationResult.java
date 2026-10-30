package com.polyzones.core.shape;

import java.util.List;

public record ValidationResult(List<ValidationIssue> issues) {
    public ValidationResult { issues = List.copyOf(issues); }
    public boolean valid() { return issues.isEmpty(); }
}
