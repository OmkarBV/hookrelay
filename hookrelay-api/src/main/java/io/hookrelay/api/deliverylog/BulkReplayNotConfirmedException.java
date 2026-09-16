package io.hookrelay.api.deliverylog;

public class BulkReplayNotConfirmedException extends RuntimeException {

    public BulkReplayNotConfirmedException() {
        super("Bulk replay requires \"confirm\": true — this can re-queue a large number of deliveries");
    }
}
