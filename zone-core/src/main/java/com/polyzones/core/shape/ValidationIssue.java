package com.polyzones.core.shape;

public record ValidationIssue(ValidationCode code, int ringIndex, String detail) { }
