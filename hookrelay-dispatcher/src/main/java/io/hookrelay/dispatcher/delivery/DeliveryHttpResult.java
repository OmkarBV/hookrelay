package io.hookrelay.dispatcher.delivery;

/**
 * The outcome of one HTTP attempt. Either {@code statusCode} is set (the
 * receiver responded, however unhappily) or {@code errorType} is (the
 * exchange never completed at all — timeout, connection refused, etc.).
 */
public record DeliveryHttpResult(
        Integer statusCode, String responseBodyTruncated, int latencyMs, String errorType) {

    public static DeliveryHttpResult ofResponse(int statusCode, String responseBodyTruncated, int latencyMs) {
        return new DeliveryHttpResult(statusCode, responseBodyTruncated, latencyMs, null);
    }

    public static DeliveryHttpResult ofNetworkError(String errorType, int latencyMs) {
        return new DeliveryHttpResult(null, null, latencyMs, errorType);
    }

    public boolean isNetworkError() {
        return statusCode == null;
    }
}
