package com.polyzones.core.geo;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class HaversineTest {
    @Test void distanceIsSymmetricAndIdentityIsZero() {
        LonLat a = new LonLat(77.59,12.97), b = new LonLat(28.6,60);
        assertThat(Haversine.distanceMeters(a,a)).isZero();
        assertThat(Haversine.distanceMeters(a,b)).isEqualTo(Haversine.distanceMeters(b,a));
    }
    @Test void lerpIsLinearInDegrees() {
        assertThat(Haversine.lerp(new LonLat(0,0),new LonLat(10,4),.5)).isEqualTo(new LonLat(5,2));
    }
}
