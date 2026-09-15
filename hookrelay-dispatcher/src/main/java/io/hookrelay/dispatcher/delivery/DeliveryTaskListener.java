package io.hookrelay.dispatcher.delivery;

import io.hookrelay.common.delivery.DeliveryTaskMessage;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class DeliveryTaskListener {

    private final DeliveryExecutionService deliveryExecutionService;

    public DeliveryTaskListener(DeliveryExecutionService deliveryExecutionService) {
        this.deliveryExecutionService = deliveryExecutionService;
    }

    @KafkaListener(topics = "webhook.deliveries")
    public void onMessage(DeliveryTaskMessage message) {
        deliveryExecutionService.execute(message.deliveryId());
    }
}
