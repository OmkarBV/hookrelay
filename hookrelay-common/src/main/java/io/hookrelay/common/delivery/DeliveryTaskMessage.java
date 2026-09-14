package io.hookrelay.common.delivery;

import java.util.UUID;

/**
 * The Kafka message body on {@code webhook.deliveries}, shared between the
 * hookrelay-api producer and the hookrelay-dispatcher consumer. Deliberately
 * just an id: the delivery row in Postgres is the source of truth, so the
 * dispatcher loads everything else it needs from there. Keeping this
 * message tiny also means the Phase 5 retry sweeper can republish the exact
 * same shape when re-queuing a delivery — ingestion and retry both just say
 * "go look at this delivery."
 */
public record DeliveryTaskMessage(UUID deliveryId) {
}
