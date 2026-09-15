package io.hookrelay.dispatcher.delivery;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * The default backoff schedule from the spec: 5s, 30s, 2m, 10m, 1h, 6h, 24h,
 * then EXHAUSTED. This is intentionally the simplest possible implementation
 * — a fixed schedule, no jitter, not configurable per endpoint. Phase 5 owns
 * making it configurable and adding jitter (to avoid many deliveries that
 * failed at the same moment retrying in lockstep); this class is the seam
 * it will replace.
 */
final class RetryBackoff {

    private static final Duration[] SCHEDULE = {
            Duration.ofSeconds(5),
            Duration.ofSeconds(30),
            Duration.ofMinutes(2),
            Duration.ofMinutes(10),
            Duration.ofHours(1),
            Duration.ofHours(6),
            Duration.ofHours(24),
    };

    private RetryBackoff() {
    }

    /** @param failureCount the number of failed attempts so far, including the one just made */
    static Optional<Instant> nextAttemptAt(int failureCount) {
        if (failureCount > SCHEDULE.length) {
            return Optional.empty();
        }
        return Optional.of(Instant.now().plus(SCHEDULE[failureCount - 1]));
    }
}
