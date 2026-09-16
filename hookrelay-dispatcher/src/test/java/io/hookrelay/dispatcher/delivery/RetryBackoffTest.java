package io.hookrelay.dispatcher.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class RetryBackoffTest {

    @Test
    void firstFailureRetriesAroundFiveSeconds() {
        Instant before = Instant.now();
        Instant next = RetryBackoff.nextAttemptAt(1, null).orElseThrow();
        // 5s base, equal jitter means actual delay is in [2s, 4s] (integer
        // seconds); measuring from `before` (captured pre-call) rather than
        // a fresh Instant.now() avoids a flaky sub-millisecond race at the
        // lower bound.
        assertThat(Duration.between(before, next))
                .isBetween(Duration.ofMillis(1900), Duration.ofSeconds(6));
    }

    @Test
    void scheduleIsMonotonicallyIncreasing() {
        Instant prev = Instant.EPOCH;
        for (int failureCount = 1; failureCount <= 7; failureCount++) {
            Instant next = RetryBackoff.nextAttemptAt(failureCount, null).orElseThrow();
            assertThat(next).isAfter(prev);
            prev = next;
        }
    }

    @Test
    void exhaustsAfterTheEighthFailure() {
        assertThat(RetryBackoff.nextAttemptAt(8, null)).isEmpty();
    }

    @Test
    void jitterStaysWithinTheEqualJitterBounds() {
        // Repeat many times to exercise the random range without flaking.
        for (int i = 0; i < 200; i++) {
            Instant before = Instant.now();
            Instant next = RetryBackoff.nextAttemptAt(3, null).orElseThrow(); // base = 120s
            Duration delay = Duration.between(before, next);
            assertThat(delay).isBetween(Duration.ofSeconds(59), Duration.ofSeconds(121));
        }
    }

    @Test
    void endpointOverrideReplacesTheDefaultScheduleEntirely() {
        Integer[] shortSchedule = {1, 2};

        assertThat(RetryBackoff.nextAttemptAt(1, shortSchedule)).isPresent();
        assertThat(RetryBackoff.nextAttemptAt(2, shortSchedule)).isPresent();
        assertThat(RetryBackoff.nextAttemptAt(3, shortSchedule)).isEmpty();
    }

    @Test
    void emptyOverrideFallsBackToDefault() {
        assertThat(RetryBackoff.nextAttemptAt(7, new Integer[0])).isPresent();
        assertThat(RetryBackoff.nextAttemptAt(8, new Integer[0])).isEmpty();
    }
}
