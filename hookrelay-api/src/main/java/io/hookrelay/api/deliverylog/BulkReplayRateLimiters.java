package io.hookrelay.api.deliverylog;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * One token-bucket rate limiter per tenant, guarding bulk replay
 * specifically — it can trivially re-queue thousands of deliveries in one
 * request, which is exactly the kind of operation that deserves its own
 * limit independent of Phase 7's general ingestion/delivery rate limiting.
 */
@Component
public class BulkReplayRateLimiters {

    private final RateLimiterRegistry registry;

    public BulkReplayRateLimiters(
            @Value("${hookrelay.replay.bulk.rate-limit-per-minute:5}") int limitPerMinute) {
        RateLimiterConfig config = RateLimiterConfig.custom()
                .limitForPeriod(limitPerMinute)
                .limitRefreshPeriod(Duration.ofMinutes(1))
                .timeoutDuration(Duration.ZERO)
                .build();
        this.registry = RateLimiterRegistry.of(config);
    }

    public RateLimiter forTenant(UUID tenantId) {
        return registry.rateLimiter(tenantId.toString());
    }
}
