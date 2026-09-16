package io.hookrelay.common.delivery;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes to {@code webhook.deliveries}, partitioned by endpointId. That
 * partition key — not eventId or deliveryId — is what gives per-endpoint
 * ordering: every delivery for the same endpoint lands on the same
 * partition and is consumed in order, while different endpoints spread
 * across partitions run in parallel. Partitioning by eventId would scatter
 * one endpoint's deliveries across partitions with no ordering guarantee;
 * partitioning by deliveryId (always unique) would do the same.
 *
 * <p>Shared between hookrelay-api (the initial publish, right after
 * ingestion commits) and hookrelay-dispatcher (the retry sweeper
 * republishing a FAILED delivery once its nextAttemptAt has passed) —
 * both are "make this delivery get attempted" in exactly the same shape,
 * just triggered from different places.
 *
 * <p>Fire-and-forget on purpose: the delivery row is already durably
 * committed by the time this is called, so a dropped or slow publish here
 * does not lose the delivery — the retry sweeper (or, for the very first
 * publish, the same sweeper once nextAttemptAt passes) picks up any row
 * whose last attempt didn't land. Blocking on the Kafka ack would only add
 * latency for no correctness benefit.
 */
@Component
public class DeliveryPublisher {

    private static final Logger log = LoggerFactory.getLogger(DeliveryPublisher.class);
    private static final String TOPIC = "webhook.deliveries";

    private final KafkaTemplate<String, DeliveryTaskMessage> kafkaTemplate;

    public DeliveryPublisher(KafkaTemplate<String, DeliveryTaskMessage> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publish(UUID deliveryId, UUID endpointId) {
        kafkaTemplate.send(TOPIC, endpointId.toString(), new DeliveryTaskMessage(deliveryId))
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.warn("Failed to publish delivery task for delivery {}; the retry sweeper will "
                                + "pick it up once its nextAttemptAt passes", deliveryId, ex);
                    }
                });
    }
}
