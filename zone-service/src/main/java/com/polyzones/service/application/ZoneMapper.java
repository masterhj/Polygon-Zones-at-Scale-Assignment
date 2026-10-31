package com.polyzones.service.application;

import com.polyzones.core.geo.LonLat;
import com.polyzones.core.index.ZoneSnapshot;
import com.polyzones.core.shape.PolygonShape;
import com.polyzones.core.zone.Zone;
import com.polyzones.service.persistence.GeometryCodec;
import com.polyzones.service.web.dto.PolygonDto;
import com.polyzones.service.web.dto.ZoneResponse;
import java.util.ArrayList;
import java.util.List;

/** Maps transport geometry to the validated core model and back. */
public final class ZoneMapper {
    private final GeometryCodec codec;
    public ZoneMapper(GeometryCodec codec) { this.codec = codec; }

    public PolygonShape toShape(PolygonDto dto) {
        if (!"Polygon".equals(dto.type())) throw new IllegalArgumentException("Geometry type must be Polygon");
        if (dto.coordinates() == null || dto.coordinates().isEmpty()) throw new IllegalArgumentException("Polygon rings are required");
        List<List<LonLat>> rings = new ArrayList<>();
        for (List<List<Double>> ring : dto.coordinates()) {
            List<LonLat> points = new ArrayList<>();
            for (List<Double> coordinate : ring) {
                if (coordinate == null || coordinate.size() != 2 || coordinate.get(0) == null || coordinate.get(1) == null)
                    throw new IllegalArgumentException("Coordinates must contain longitude and latitude");
                points.add(new LonLat(coordinate.get(0), coordinate.get(1)));
            }
            rings.add(List.copyOf(points));
        }
        return PolygonShape.of(rings.getFirst(), rings.subList(1, rings.size()), com.polyzones.core.geo.GeometryTolerance.DEFAULT);
    }

    public PolygonDto toDto(PolygonShape shape) {
        return codec.toDto(shape);
    }

    public String canonicalGeometry(PolygonShape shape) { return codec.encode(shape); }

    public ZoneResponse toResponse(Zone zone, long setVersion) {
        return new ZoneResponse(zone.id().value(), zone.version(), zone.type(), zone.priority(), setVersion, toDto(zone.shape()));
    }

    public ZoneResponse toResponse(ZoneSnapshot snapshot, Zone zone) { return toResponse(zone, snapshot.setVersion()); }
}
