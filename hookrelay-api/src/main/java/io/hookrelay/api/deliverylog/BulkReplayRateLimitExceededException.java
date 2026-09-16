package io.hookrelay.api.deliverylog;

public class BulkReplayRateLimitExceededException extends RuntimeException {

    public BulkReplayRateLimitExceededException() {
        super("Bulk replay rate limit exceeded for this tenant — try again shortly");
    }
}
