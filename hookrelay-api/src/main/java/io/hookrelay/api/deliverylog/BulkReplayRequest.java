package io.hookrelay.api.deliverylog;

import io.hookrelay.common.delivery.DeliveryStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * Every filter is optional except {@code confirm} — an empty filter would
 * match every delivery ever recorded for the tenant, so {@code confirm}
 * exists specifically to stop a bulk replay from being triggered by
 * accident (a missing field, a copy-pasted request) rather than a
 * deliberate decision to re-queue exactly what the filter describes.
 */
public record BulkReplayRequest(
        UUID endpointId,
        DeliveryStatus status,
        String eventType,
        Instant from,
        Instant to,
        boolean confirm) {
}
