package io.hookrelay.dispatcher.delivery;

import io.hookrelay.common.delivery.Delivery;
import io.hookrelay.common.delivery.DeliveryAttempt;
import io.hookrelay.common.delivery.DeliveryAttemptRepository;
import io.hookrelay.common.delivery.DeliveryRepository;
import io.hookrelay.dispatcher.observability.DeliveryMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(DeliveryOutcomeRecorder.class);

    private final DeliveryRepository deliveryRepository;
    private final DeliveryAttemptRepository deliveryAttemptRepository;
    private final DeliveryMetrics metrics;

    public DeliveryOutcomeRecorder(
            DeliveryRepository deliveryRepository, DeliveryAttemptRepository deliveryAttemptRepository,
            DeliveryMetrics metrics) {
        this.deliveryRepository = deliveryRepository;
        this.deliveryAttemptRepository = deliveryAttemptRepository;
        this.metrics = metrics;
    }

    @Transactional
    public void record(Delivery delivery, DeliveryAttempt attempt) {
        deliveryAttemptRepository.save(attempt);
        deliveryRepository.save(delivery);
        metrics.recordAttempt(delivery.getStatus(), attempt.getErrorType(), attempt.getLatencyMs());
        // The one log line every delivery attempt is guaranteed to produce,
        // successful or not — with MDC's correlationId (set by
        // DeliveryExecutionService around this call), it's what makes "find
        // every log line for this request, from ingestion through final
        // delivery" actually possible instead of only true for the
        // exceptional paths that already logged something.
        log.info("Delivery {} attempt #{} to endpoint {}: {}", delivery.getId(), attempt.getAttemptNumber(),
                delivery.getEndpoint().getId(), attempt.getErrorType() == null ? "SUCCESS" : attempt.getErrorType());
    }
}
