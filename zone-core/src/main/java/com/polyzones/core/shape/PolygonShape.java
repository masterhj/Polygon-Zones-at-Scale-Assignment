package com.polyzones.core.shape;

import com.polyzones.core.geo.BoundingBox;
import com.polyzones.core.geo.GeometryTolerance;
import com.polyzones.core.geo.Location;
import com.polyzones.core.geo.LonLat;
import java.util.List;

/** Validated immutable polygon with a closed-set boundary rule. */
public final class PolygonShape {
    private final Ring outer;
    private final List<Ring> holes;
    private final BoundingBox bounds;
    private final int vertexCount;
    private PolygonShape(Ring outer, List<Ring> holes) {
        this.outer = outer; this.holes = List.copyOf(holes); this.bounds = outer.bounds();
        this.vertexCount = outer.vertexCount() + holes.stream().mapToInt(Ring::vertexCount).sum();
    }
    public static PolygonShape of(List<LonLat> outer, List<List<LonLat>> holes, GeometryTolerance tol) {
        ValidationResult result = PolygonValidator.validate(outer, holes, tol);
        if (!result.valid()) throw new InvalidPolygonException(result);
        return new PolygonShape(new Ring(outer, true, tol.boundaryEpsDeg()), holes.stream().map(h -> new Ring(h, false, tol.boundaryEpsDeg())).toList());
    }
    public Ring outer() { return outer; }
    public List<Ring> holes() { return holes; }
    public BoundingBox bounds() { return bounds; }
    public int vertexCount() { return vertexCount; }
    public Location locate(double lon, double lat, GeometryTolerance tol) {
        Location outerLocation = outer.locate(lon, lat, tol);
        if (outerLocation == Location.OUTSIDE) return Location.OUTSIDE;
        if (outerLocation == Location.BOUNDARY) return Location.BOUNDARY;
        for (Ring hole : holes) {
            if (!hole.bounds().contains(lon, lat, tol.boundaryEpsDeg())) continue;
            Location location = hole.locate(lon, lat, tol);
            if (location == Location.BOUNDARY) return Location.BOUNDARY;
            if (location == Location.INSIDE) return Location.OUTSIDE;
        }
        return Location.INSIDE;
    }
    public boolean contains(double lon, double lat, GeometryTolerance tol) { return locate(lon, lat, tol) != Location.OUTSIDE; }
}
