package io.hookrelay.dispatcher.delivery;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * The default backoff schedule from the spec: 5s, 30s, 2m, 10m, 1h, 6h, 24h,
 * then EXHAUSTED. An endpoint can override it entirely via
 * {@code Endpoint.retryScheduleSeconds}; a null or empty override falls back
 * to this default.
 *
 * <p>"Equal jitter" is applied to whichever schedule is in use: half of the
 * base delay is fixed, the other half is randomized (delay/2 +
 * random(0, delay/2)). Without jitter, every delivery that failed at the
 * same moment (a receiver's brief outage takes down many endpoints' worth
 * of deliveries at once) would retry in lockstep, turning a recovery into a
 * thundering herd against a system that's just come back up. Equal jitter
 * keeps retries spread out while still respecting the schedule's intent —
 * unlike full jitter (random(0, delay)), it never collapses a "wait an
 * hour" step down to "wait almost no time at all."
 */
final class RetryBackoff {

    private static final int[] DEFAULT_SCHEDULE_SECONDS = {5, 30, 120, 600, 3600, 21600, 86400};
    private static final SecureRandom RANDOM = new SecureRandom();

    private RetryBackoff() {
    }

    /** @param failureCount the number of failed attempts so far, including the one just made */
    static Optional<Instant> nextAttemptAt(int failureCount, Integer[] endpointOverride) {
        int[] schedule = toScheduleArray(endpointOverride);
        if (failureCount > schedule.length) {
            return Optional.empty();
        }
        long baseSeconds = schedule[failureCount - 1];
        long jitteredSeconds = baseSeconds / 2 + RANDOM.nextLong(Math.max(baseSeconds / 2, 1) + 1);
        return Optional.of(Instant.now().plus(Duration.ofSeconds(jitteredSeconds)));
    }

    private static int[] toScheduleArray(Integer[] endpointOverride) {
        if (endpointOverride == null || endpointOverride.length == 0) {
            return DEFAULT_SCHEDULE_SECONDS;
        }
        int[] schedule = new int[endpointOverride.length];
        for (int i = 0; i < endpointOverride.length; i++) {
            schedule[i] = endpointOverride[i];
        }
        return schedule;
    }
}
