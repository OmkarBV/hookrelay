package io.hookrelay.dispatcher.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class RetryBackoffTest {

    @Test
    void firstFailureRetriesAroundFiveSeconds() {
        Instant next = RetryBackoff.nextAttemptAt(1).orElseThrow();
        assertThat(Duration.between(Instant.now(), next)).isCloseTo(Duration.ofSeconds(5), Duration.ofSeconds(2));
    }

    @Test
    void scheduleIsMonotonicallyIncreasing() {
        Instant prev = Instant.EPOCH;
        for (int failureCount = 1; failureCount <= 7; failureCount++) {
            Instant next = RetryBackoff.nextAttemptAt(failureCount).orElseThrow();
            assertThat(next).isAfter(prev);
            prev = next;
        }
    }

    @Test
    void exhaustsAfterTheEighthFailure() {
        assertThat(RetryBackoff.nextAttemptAt(8)).isEmpty();
    }
}
