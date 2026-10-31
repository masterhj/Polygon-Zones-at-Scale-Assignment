package com.polyzones.service.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BackpressureGuardTest {
    @Test void admissionReservationIsWholeRequestAndReleasedExactlyOnce() {
        BackpressureGuard guard = new BackpressureGuard(3);
        assertThat(guard.reserve(4)).isNull();
        assertThat(guard.availableCapacity()).isEqualTo(3);
        BackpressureGuard.Reservation reservation = guard.reserve(3);
        assertThat(reservation).isNotNull();
        assertThat(guard.availableCapacity()).isZero();
        reservation.close();
        reservation.close();
        assertThat(guard.availableCapacity()).isEqualTo(3);
    }
}
