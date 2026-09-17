package io.hookrelay.dispatcher.delivery;

import java.time.Duration;

/**
 * The outcome of one HTTP attempt. Either {@code statusCode} is set (the
 * receiver responded, however unhappily) or {@code errorType} is (the
 * exchange never completed at all — timeout, connection refused, etc.).
 * {@code retryAfter} is populated only when the receiver's response
 * included a {@code Retry-After} header — see DeliveryExecutionService for
 * why a 429 with that header should schedule the next attempt from it
 * rather than the default backoff schedule.
 */
public record DeliveryHttpResult(
        Integer statusCode, String responseBodyTruncated, int latencyMs, String errorType, Duration retryAfter) {

    public static DeliveryHttpResult ofResponse(
            int statusCode, String responseBodyTruncated, int latencyMs, Duration retryAfter) {
        return new DeliveryHttpResult(statusCode, responseBodyTruncated, latencyMs, null, retryAfter);
    }

    public static DeliveryHttpResult ofNetworkError(String errorType, int latencyMs) {
        return new DeliveryHttpResult(null, null, latencyMs, errorType, null);
    }

    public boolean isNetworkError() {
        return statusCode == null;
    }
}
