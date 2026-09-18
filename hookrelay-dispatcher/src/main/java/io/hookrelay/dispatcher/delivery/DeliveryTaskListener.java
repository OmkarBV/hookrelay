package io.hookrelay.dispatcher.delivery;

import io.hookrelay.common.delivery.DeliveryTaskMessage;
import io.hookrelay.dispatcher.observability.DeliveryMetrics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class DeliveryTaskListener {

    private final DeliveryExecutionService deliveryExecutionService;
    private final DeliveryMetrics metrics;

    public DeliveryTaskListener(DeliveryExecutionService deliveryExecutionService, DeliveryMetrics metrics) {
        this.deliveryExecutionService = deliveryExecutionService;
        this.metrics = metrics;
    }

    @KafkaListener(topics = "webhook.deliveries")
    public void onMessage(ConsumerRecord<String, DeliveryTaskMessage> record) {
        // The Kafka record timestamp is when this task was produced (either
        // by ingestion's initial publish or the retry sweeper's republish) —
        // the gap between that and right now is how long it waited in the
        // topic before this dispatcher instance got to it.
        metrics.recordQueueLag(System.currentTimeMillis() - record.timestamp());
        deliveryExecutionService.execute(record.value().deliveryId());
    }
}
