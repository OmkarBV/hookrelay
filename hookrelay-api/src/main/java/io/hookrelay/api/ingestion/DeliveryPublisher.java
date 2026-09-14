package io.hookrelay.api.ingestion;

import io.hookrelay.common.delivery.DeliveryTaskMessage;
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
 * <p>Fire-and-forget on purpose: the delivery row is already durably
 * committed by the time this is called (see EventIngestionService), so a
 * dropped or slow publish here does not lose the delivery — the Phase 5
 * retry sweeper picks up any PENDING row whose nextAttemptAt has passed
 * without a successful attempt. Blocking on the Kafka ack would only add
 * latency to the ingestion request for no correctness benefit.
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
