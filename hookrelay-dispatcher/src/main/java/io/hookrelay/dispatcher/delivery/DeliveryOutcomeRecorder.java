package io.hookrelay.dispatcher.delivery;

import io.hookrelay.common.delivery.Delivery;
import io.hookrelay.common.delivery.DeliveryAttempt;
import io.hookrelay.common.delivery.DeliveryAttemptRepository;
import io.hookrelay.common.delivery.DeliveryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Saves a DeliveryAttempt row and the owning Delivery's updated status in
 * one transaction. This has to be a separate bean from
 * DeliveryExecutionService: {@code @Transactional} is proxy-based, so a call
 * from one method to another on the *same* object (self-invocation) never
 * goes through the proxy and silently runs without a transaction at all —
 * exactly the pitfall called out in EventIngestionService. Calling through a
 * distinct bean is what makes the interception happen.
 */
@Service
public class DeliveryOutcomeRecorder {

    private final DeliveryRepository deliveryRepository;
    private final DeliveryAttemptRepository deliveryAttemptRepository;

    public DeliveryOutcomeRecorder(
            DeliveryRepository deliveryRepository, DeliveryAttemptRepository deliveryAttemptRepository) {
        this.deliveryRepository = deliveryRepository;
        this.deliveryAttemptRepository = deliveryAttemptRepository;
    }

    @Transactional
    public void record(Delivery delivery, DeliveryAttempt attempt) {
        deliveryAttemptRepository.save(attempt);
        deliveryRepository.save(delivery);
    }
}
