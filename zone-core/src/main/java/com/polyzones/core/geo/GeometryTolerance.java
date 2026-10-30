package com.polyzones.core.geo;

/** Numeric tolerances; defaults follow D-03. */
public record GeometryTolerance(double boundaryEpsDeg, double tEps) {
    public static final GeometryTolerance DEFAULT = new GeometryTolerance(1e-9, 1e-12);
    public GeometryTolerance {
        if (!(boundaryEpsDeg >= 0) || !Double.isFinite(boundaryEpsDeg) || !(tEps >= 0) || !Double.isFinite(tEps)) throw new IllegalArgumentException("Tolerances must be finite and non-negative");
    }
}
