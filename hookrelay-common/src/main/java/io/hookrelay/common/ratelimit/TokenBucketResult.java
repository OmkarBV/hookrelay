package io.hookrelay.common.ratelimit;

import java.time.Duration;

public record TokenBucketResult(boolean allowed, double tokensRemaining, Duration retryAfter) {
}
