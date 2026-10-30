package com.polyzones.core.geo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import org.junit.jupiter.api.Test;

class GeometryValuesTest {
    @Test void finiteCoordinatesAndBoxOperations() {
        assertThat(LonLat.of(2, 3)).isEqualTo(new LonLat(2, 3));
        assertThatIllegalArgumentException().isThrownBy(() -> LonLat.of(Double.NaN, 0));
        BoundingBox a = new BoundingBox(0, 0, 2, 2);
        assertThat(a.contains(2, 2, 0)).isTrue();
        assertThat(a.expand(1)).isEqualTo(new BoundingBox(-1, -1, 3, 3));
        assertThat(a.union(new BoundingBox(1, -2, 4, 1))).isEqualTo(new BoundingBox(0, -2, 4, 2));
        assertThat(a.intersects(new BoundingBox(2, 1, 3, 2))).isTrue();
    }
}
