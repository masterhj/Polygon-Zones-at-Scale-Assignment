package com.polyzones.service.persistence;

import com.polyzones.core.geo.GeometryTolerance;
import com.polyzones.core.geo.LonLat;
import com.polyzones.core.shape.PolygonShape;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;
import com.polyzones.service.web.dto.PolygonDto;

/** The sole GeoJSON text codec used by service persistence. Coordinates are always longitude first. */
public final class GeometryCodec {
    private final JsonMapper mapper;

    public GeometryCodec(JsonMapper mapper) { this.mapper = mapper; }

    public String encode(PolygonShape shape) {
        ObjectNode root = mapper.createObjectNode();
        root.put("type", "Polygon");
        ArrayNode rings = root.putArray("coordinates");
        appendRing(rings.addArray(), shape.outer());
        shape.holes().forEach(hole -> appendRing(rings.addArray(), hole));
        return mapper.writeValueAsString(root);
    }

    public PolygonShape decode(String geoJson) {
        JsonNode root = mapper.readTree(geoJson);
        if (root == null || !"Polygon".equals(root.path("type").asString())) throw new IllegalArgumentException("Stored geometry is not a Polygon");
        JsonNode rings = root.path("coordinates");
        if (!rings.isArray() || rings.isEmpty()) throw new IllegalArgumentException("Stored polygon has no rings");
        List<LonLat> outer = readRing(rings.get(0));
        List<List<LonLat>> holes = new ArrayList<>();
        for (int i = 1; i < rings.size(); i++) holes.add(readRing(rings.get(i)));
        return PolygonShape.of(outer, holes, GeometryTolerance.DEFAULT);
    }

    public PolygonDto toDto(PolygonShape shape) {
        List<List<Double>> outer = dtoRing(shape.outer());
        List<List<List<Double>>> rings = new ArrayList<>();
        rings.add(outer);
        shape.holes().forEach(hole -> rings.add(dtoRing(hole)));
        return new PolygonDto("Polygon", rings);
    }

    private static List<List<Double>> dtoRing(com.polyzones.core.shape.Ring ring) {
        List<List<Double>> result = new ArrayList<>();
        for (int i = 0; i <= ring.edgeCount(); i++) {
            LonLat point = ring.vertexAt(i % ring.edgeCount());
            result.add(List.of(point.lon(), point.lat()));
        }
        return List.copyOf(result);
    }

    private static List<LonLat> readRing(JsonNode ring) {
        if (ring == null || !ring.isArray()) throw new IllegalArgumentException("Stored ring is malformed");
        List<LonLat> points = new ArrayList<>();
        for (JsonNode point : ring) {
            if (!point.isArray() || point.size() != 2 || !point.get(0).isNumber() || !point.get(1).isNumber())
                throw new IllegalArgumentException("Stored coordinate is malformed");
            points.add(new LonLat(point.get(0).asDouble(), point.get(1).asDouble()));
        }
        return List.copyOf(points);
    }

    private static void appendRing(ArrayNode ring, com.polyzones.core.shape.Ring source) {
        for (int i = 0; i <= source.edgeCount(); i++) {
            LonLat vertex = source.vertexAt(i % source.edgeCount());
            ArrayNode coordinate = ring.addArray();
            coordinate.add(vertex.lon());
            coordinate.add(vertex.lat());
        }
    }
}
