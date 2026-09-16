package io.hookrelay.api.deliverylog;

import io.hookrelay.common.delivery.Delivery;
import io.hookrelay.common.delivery.DeliveryStatus;
import java.time.Instant;
import java.util.UUID;

public record DeliveryListItemResponse(
        UUID id,
        UUID eventId,
        UUID endpointId,
        String eventType,
        DeliveryStatus status,
        int attemptCount,
        Instant nextAttemptAt,
        String lastError,
        Instant createdAt,
        Instant completedAt,
        boolean isReplay,
        UUID replayedFromDeliveryId) {

    static DeliveryListItemResponse from(Delivery delivery) {
        return new DeliveryListItemResponse(
                delivery.getId(),
                delivery.getEvent().getId(),
                delivery.getEndpoint().getId(),
                delivery.getEvent().getEventType(),
                delivery.getStatus(),
                delivery.getAttemptCount(),
                delivery.getNextAttemptAt(),
                delivery.getLastError(),
                delivery.getCreatedAt(),
                delivery.getCompletedAt(),
                delivery.isReplay(),
                delivery.getReplayedFromDeliveryId());
    }
}
