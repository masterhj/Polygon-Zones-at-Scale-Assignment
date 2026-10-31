package com.polyzones.service.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.polyzones.core.geo.LonLat;
import com.polyzones.core.trip.Ping;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReorderBufferTest {
    @Test void ordersBufferedPingsAndClassifiesSkippedAsLate() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        ReorderBuffer buffer = new ReorderBuffer(0, 64, Duration.ofSeconds(2), clock, List.of());
        assertThat(buffer.offer(ping(1)).disposition()).isEqualTo(ReorderBuffer.Disposition.BUFFERED);
        assertThat(buffer.offer(ping(0)).ready()).extracting(Ping::seq).containsExactly(0L, 1L);
        assertThat(buffer.offer(ping(1)).disposition()).isEqualTo(ReorderBuffer.Disposition.DUPLICATE);

        assertThat(buffer.offer(ping(4)).disposition()).isEqualTo(ReorderBuffer.Disposition.BUFFERED);
        clock.advance(Duration.ofMillis(2001));
        ReorderBuffer.Offer expired = buffer.releaseExpired();
        assertThat(expired.skipped()).isNotNull().extracting("firstSeq", "lastSeq").containsExactly(2L, 3L);
        assertThat(expired.ready()).extracting(Ping::seq).containsExactly(4L);
        assertThat(buffer.offer(ping(3)).disposition()).isEqualTo(ReorderBuffer.Disposition.LATE);
        assertThat(buffer.offer(ping(4)).disposition()).isEqualTo(ReorderBuffer.Disposition.DUPLICATE);
    }

    @Test void bufferIsBoundedAndRestoresSkippedRanges() {
        ReorderBuffer buffer = new ReorderBuffer(1, 1, Duration.ofSeconds(2), Clock.systemUTC(),
                List.of(new com.polyzones.core.trip.SkippedRange(0, 0)));
        assertThat(buffer.offer(ping(2)).disposition()).isEqualTo(ReorderBuffer.Disposition.BUFFERED);
        assertThat(buffer.offer(ping(3)).disposition()).isEqualTo(ReorderBuffer.Disposition.FULL);
        assertThat(buffer.offer(ping(0)).disposition()).isEqualTo(ReorderBuffer.Disposition.LATE);
        assertThat(buffer.bufferedCount()).isEqualTo(1);
    }

    @Test void finalLongSequenceDoesNotOverflowTheExpectedFrontier() {
        ReorderBuffer buffer = new ReorderBuffer(Long.MAX_VALUE, 1, Duration.ofSeconds(1), Clock.systemUTC(), List.of());
        assertThat(buffer.offer(ping(Long.MAX_VALUE)).disposition()).isEqualTo(ReorderBuffer.Disposition.READY);
        assertThat(buffer.offer(ping(Long.MAX_VALUE)).disposition()).isEqualTo(ReorderBuffer.Disposition.DUPLICATE);
    }

    private static Ping ping(long seq) { return new Ping(new UUID(0, 1), seq, Instant.EPOCH, new LonLat(0, 0), null); }

    private static final class MutableClock extends Clock {
        private Instant now;
        private MutableClock(Instant now) { this.now = now; }
        void advance(Duration by) { now = now.plus(by); }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
