package io.hookrelay.dispatcher.delivery;

/**
 * Thrown for a Kafka message that can never succeed no matter how many times
 * it's redelivered (its delivery row no longer exists — the endpoint or
 * event was deleted after the message was published). Propagating this out
 * of the {@code @KafkaListener} method lets the container's
 * DeadLetterPublishingRecoverer route it to the dead-letter topic instead of
 * looping on it or silently dropping it — see KafkaErrorHandlingConfig.
 */
public class UnprocessableDeliveryTaskException extends RuntimeException {

    public UnprocessableDeliveryTaskException(String message) {
        super(message);
    }
}
