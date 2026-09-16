package io.hookrelay.dispatcher.delivery;

import io.hookrelay.common.delivery.DeliveryTaskMessage;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Routes two kinds of unprocessable messages to {@code webhook.deliveries.DLT}
 * instead of looping on them or silently dropping them: malformed messages
 * (deserialization failure — see application.yml's ErrorHandlingDeserializer)
 * and well-formed messages that reference a delivery that no longer exists
 * (UnprocessableDeliveryTaskException, thrown by DeliveryExecutionService
 * when the endpoint or event behind a delivery was deleted after the
 * message was published).
 *
 * <p>Zero retries at this level (FixedBackOff(0, 0)) is deliberate: retrying
 * an actual delivery attempt is the retry sweeper's job, driven by the
 * `delivery` table, not Kafka consumer redelivery. A message that lands here
 * failed for a reason redelivery can't fix — the data it points to doesn't
 * exist, or it never parsed in the first place.
 */
@Configuration
public class KafkaErrorHandlingConfig {

    @Bean
    public NewTopic webhookDeliveriesDeadLetterTopic() {
        return TopicBuilder.name("webhook.deliveries.DLT").partitions(1).replicas(1).build();
    }

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, DeliveryTaskMessage> kafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate);
        return new DefaultErrorHandler(recoverer, new FixedBackOff(0L, 0L));
    }
}
