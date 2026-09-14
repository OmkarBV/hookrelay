package io.hookrelay.api.ingestion;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declares webhook.deliveries explicitly rather than relying on Kafka's
 * auto-create default, which typically creates single-partition topics —
 * exactly wrong here, since the partition count is what bounds how many
 * endpoints can be delivered to in parallel (see DeliveryPublisher for the
 * partition-key reasoning).
 */
@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic webhookDeliveriesTopic(
            @Value("${hookrelay.kafka.webhook-deliveries.partitions:6}") int partitions,
            @Value("${hookrelay.kafka.webhook-deliveries.replicas:1}") int replicas) {
        return TopicBuilder.name("webhook.deliveries")
                .partitions(partitions)
                .replicas(replicas)
                .build();
    }
}
