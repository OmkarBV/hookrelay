package io.hookrelay.api.deliverylog;

import io.hookrelay.common.delivery.DeliveryAttempt;
import java.time.Instant;

public record DeliveryAttemptResponse(
        int attemptNumber,
        String requestHeaders,
        Integer responseStatus,
        String responseBodyTruncated,
        Integer latencyMs,
        String errorType,
        Instant attemptedAt) {

    static DeliveryAttemptResponse from(DeliveryAttempt attempt) {
        return new DeliveryAttemptResponse(
                attempt.getAttemptNumber(),
                attempt.getRequestHeaders(),
                attempt.getResponseStatus(),
                attempt.getResponseBodyTruncated(),
                attempt.getLatencyMs(),
                attempt.getErrorType(),
                attempt.getAttemptedAt());
    }
}
