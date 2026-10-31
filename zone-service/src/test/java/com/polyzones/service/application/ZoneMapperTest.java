package com.polyzones.service.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.polyzones.core.shape.InvalidPolygonException;
import com.polyzones.service.persistence.GeometryCodec;
import com.polyzones.service.web.dto.PolygonDto;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ZoneMapperTest {
    private final ZoneMapper mapper = new ZoneMapper(new GeometryCodec(JsonMapper.builder().build()));

    @Test void mapsGeoJsonLongitudeFirstAndClosesReturnedRing() {
        PolygonDto dto = new PolygonDto("Polygon", List.of(List.of(List.of(77.0, 12.0), List.of(78.0, 12.0),
                List.of(78.0, 13.0), List.of(77.0, 13.0), List.of(77.0, 12.0))));
        var shape = mapper.toShape(dto);
        assertThat(shape.outer().vertexAt(0).lon()).isEqualTo(77.0);
        assertThat(mapper.toDto(shape).coordinates().getFirst().getFirst()).containsExactly(77.0, 12.0);
        assertThat(mapper.toDto(shape).coordinates().getFirst()).hasSize(5);
    }

    @Test void rejectsUnsupportedTypeAndInvalidPolygon() {
        assertThatThrownBy(() -> mapper.toShape(new PolygonDto("LineString", List.of()))).isInstanceOf(IllegalArgumentException.class);
        PolygonDto open = new PolygonDto("Polygon", List.of(List.of(List.of(0.0, 0.0), List.of(1.0, 0.0), List.of(1.0, 1.0))));
        assertThatThrownBy(() -> mapper.toShape(open)).isInstanceOf(InvalidPolygonException.class);
    }
}
