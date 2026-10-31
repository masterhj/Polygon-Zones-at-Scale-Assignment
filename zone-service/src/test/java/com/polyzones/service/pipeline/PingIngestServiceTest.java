package com.polyzones.service.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.polyzones.core.geo.LonLat;
import com.polyzones.core.trip.Ping;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.awaitility.Awaitility.await;

class PingIngestServiceTest {
    @Test void boundedAdmissionRejectsWholeRequestAndDrainsOnClose() throws Exception {
        UUID tenant = new UUID(0, 1), trip = new UUID(0, 2);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        PingIngestService service = new PingIngestService(2, 2, Clock.fixed(Instant.EPOCH, java.time.ZoneOffset.UTC),
                Duration.ofHours(24), Duration.ofMinutes(5), (t, ping) -> {
                    entered.countDown();
                    try { release.await(); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                });
        try {
            var response = service.ingest(tenant, trip, List.of(ping(trip, 0), ping(trip, 1)));
            assertThat(response.items()).extracting(PingIngestService.ItemResult::status)
                    .containsExactly(PingIngestService.Status.ACCEPTED, PingIngestService.Status.ACCEPTED);
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> service.ingest(tenant, trip, List.of(ping(trip, 2))))
                    .isInstanceOf(PingIngestService.BackpressureException.class);
            assertThat(service.queuedCount()).isEqualTo(1);
        } finally {
            release.countDown();
            service.close();
        }
    }

    @Test void validatesItemsAndBoundsRequestSize() {
        UUID tenant = new UUID(0, 1), trip = new UUID(0, 2);
        try (PingIngestService service = new PingIngestService(1, 2, Clock.fixed(Instant.EPOCH, java.time.ZoneOffset.UTC),
                Duration.ofHours(24), Duration.ofMinutes(5), (t, p) -> { })) {
            var result = service.ingest(tenant, trip, List.of(ping(trip, 0), ping(new UUID(0, 9), 1),
                    new Ping(trip, 2, Instant.parse("2020-01-01T00:00:00Z"), new LonLat(0, 0), null)));
            assertThat(result.items()).extracting(PingIngestService.ItemResult::status).containsExactly(
                    PingIngestService.Status.ACCEPTED, PingIngestService.Status.REJECTED_INVALID,
                    PingIngestService.Status.REJECTED_CLOCK_SKEW);
            assertThatThrownBy(() -> service.ingest(tenant, trip,
                    java.util.Collections.nCopies(501, ping(trip, 3)))).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void workerFailureFailsClosedAndRetainsTheUncommittedPingUntilShutdown() throws Exception {
        UUID tenant = new UUID(0, 1), trip = new UUID(0, 2);
        CountDownLatch failed = new CountDownLatch(1);
        PingIngestService service = new PingIngestService(1, 2, Clock.fixed(Instant.EPOCH, java.time.ZoneOffset.UTC),
                Duration.ofHours(24), Duration.ofMinutes(5), (t, p) -> { failed.countDown(); throw new IllegalStateException("injected"); });
        service.ingest(tenant, trip, List.of(ping(trip, 0)));
        assertThat(failed.await(2, TimeUnit.SECONDS)).isTrue();
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(service.queuedCount()).isEqualTo(1));
        assertThat(service.workerFailures()).isEqualTo(1);
        assertThatThrownBy(() -> service.ingest(tenant, trip, List.of(ping(trip, 1))))
                .isInstanceOf(IllegalStateException.class);
        service.close();
    }

    @Test void backpressureNeverEnqueuesOnlyPartOfARequest() throws Exception {
        UUID tenant = new UUID(0, 1), trip = new UUID(0, 2);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        PingIngestService service = new PingIngestService(1, 2, Clock.fixed(Instant.EPOCH, java.time.ZoneOffset.UTC),
                Duration.ofHours(24), Duration.ofMinutes(5), (t, p) -> {
                    entered.countDown();
                    try { release.await(); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                });
        try {
            service.ingest(tenant, trip, List.of(ping(trip, 0)));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> service.ingest(tenant, trip, List.of(ping(trip, 1), ping(trip, 2))))
                    .isInstanceOf(PingIngestService.BackpressureException.class);
            assertThat(service.queuedCount()).isZero();
        } finally {
            release.countDown();
            service.close();
        }
    }

    private static Ping ping(UUID trip, long seq) { return new Ping(trip, seq, Instant.EPOCH, new LonLat(0, 0), null); }
}
