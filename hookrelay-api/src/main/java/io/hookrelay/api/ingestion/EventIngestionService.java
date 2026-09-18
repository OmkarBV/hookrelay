package io.hookrelay.api.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hookrelay.common.application.Application;
import io.hookrelay.common.application.ApplicationRepository;
import io.hookrelay.common.delivery.Delivery;
import io.hookrelay.common.delivery.DeliveryPublisher;
import io.hookrelay.common.delivery.DeliveryRepository;
import io.hookrelay.common.endpoint.Endpoint;
import io.hookrelay.common.endpoint.EndpointRepository;
import io.hookrelay.common.endpoint.EndpointStatus;
import io.hookrelay.common.event.Event;
import io.hookrelay.common.event.EventRepository;
import io.hookrelay.api.observability.CorrelationIdFilter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Implements the transactional outbox pattern for ingestion: the event and
 * its fanned-out delivery rows are written in one database transaction,
 * and only once that transaction has committed does anything get published
 * to Kafka.
 *
 * <p>Publishing inside the transaction would be wrong: if a later statement
 * in the same transaction failed and rolled it back, a Kafka message would
 * already be out there pointing at a delivery row that never actually came
 * into existence — the dispatcher would fetch it and find nothing. Committing
 * first guarantees every published message refers to durably-stored state.
 * The converse risk — the process crashing after commit but before the
 * publish call — is accepted here for latency (see DeliveryPublisher): the
 * delivery row is already PENDING with a due nextAttemptAt, so the Phase 5
 * retry sweeper closes that gap without ingestion having to wait on Kafka.
 *
 * <p>{@link TransactionTemplate} is used explicitly (rather than
 * {@code @Transactional}) because the retry-on-idempotency-conflict logic
 * needs one transaction to fail and fully roll back, then a second, separate
 * transaction to read the row it collided with — Postgres refuses further
 * statements on a transaction after a constraint violation, so both halves
 * cannot share one transaction. Annotation-driven self-invocation across two
 * {@code @Transactional} methods on the same bean would also silently skip
 * the proxy, so explicit transaction control avoids that pitfall too.
 */
@Service
public class EventIngestionService {

    private static final int MAX_PAYLOAD_BYTES = 256 * 1024;

    private final ApplicationRepository applicationRepository;
    private final EventRepository eventRepository;
    private final EndpointRepository endpointRepository;
    private final DeliveryRepository deliveryRepository;
    private final DeliveryPublisher deliveryPublisher;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public EventIngestionService(
            ApplicationRepository applicationRepository,
            EventRepository eventRepository,
            EndpointRepository endpointRepository,
            DeliveryRepository deliveryRepository,
            DeliveryPublisher deliveryPublisher,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager) {
        this.applicationRepository = applicationRepository;
        this.eventRepository = eventRepository;
        this.endpointRepository = endpointRepository;
        this.deliveryRepository = deliveryRepository;
        this.deliveryPublisher = deliveryPublisher;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public record IngestResult(UUID eventId, boolean duplicate) {
    }

    public IngestResult ingest(UUID applicationId, IngestEventRequest request, String idempotencyKeyHeader) {
        String payloadJson = writePayload(request.payload());
        int payloadSizeBytes = payloadJson.getBytes(StandardCharsets.UTF_8).length;
        if (payloadSizeBytes > MAX_PAYLOAD_BYTES) {
            throw new PayloadTooLargeException(MAX_PAYLOAD_BYTES);
        }

        // eventId in the request body and the Idempotency-Key header are the
        // same concept expressed two ways (see RECEIVERS.md); the header
        // wins if a caller inexplicably sends both with different values.
        String idempotencyKey = idempotencyKeyHeader != null ? idempotencyKeyHeader : request.eventId();

        FanOutResult fanOut;
        try {
            fanOut = transactionTemplate.execute(status ->
                    createEventAndFanOut(applicationId, request.eventType(), payloadJson, idempotencyKey, payloadSizeBytes));
        } catch (DataIntegrityViolationException e) {
            if (idempotencyKey == null) {
                throw e;
            }
            Event existing = transactionTemplate.execute(status ->
                    eventRepository.findByApplicationIdAndIdempotencyKey(applicationId, idempotencyKey).orElse(null));
            if (existing == null) {
                throw e;
            }
            return new IngestResult(existing.getId(), true);
        }

        for (DeliveryTarget target : fanOut.deliveryTargets()) {
            deliveryPublisher.publish(target.deliveryId(), target.endpointId());
        }
        return new IngestResult(fanOut.event().getId(), false);
    }

    private record DeliveryTarget(UUID deliveryId, UUID endpointId) {
    }

    private record FanOutResult(Event event, List<DeliveryTarget> deliveryTargets) {
    }

    private FanOutResult createEventAndFanOut(
            UUID applicationId, String eventType, String payloadJson, String idempotencyKey, int payloadSizeBytes) {
        Application application = applicationRepository.findById(applicationId)
                .orElseThrow(() -> new IllegalArgumentException("Application not found: " + applicationId));

        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (correlationId == null) {
            correlationId = UUID.randomUUID().toString();
        }
        Event event = eventRepository.saveAndFlush(
                new Event(application, eventType, payloadJson, idempotencyKey, payloadSizeBytes, correlationId));

        List<Endpoint> subscribedEndpoints =
                endpointRepository.findSubscribed(applicationId, EndpointStatus.ACTIVE, eventType);

        List<DeliveryTarget> targets = subscribedEndpoints.stream()
                .map(endpoint -> deliveryRepository.save(new Delivery(event, endpoint)))
                .map(delivery -> new DeliveryTarget(delivery.getId(), delivery.getEndpoint().getId()))
                .toList();

        return new FanOutResult(event, targets);
    }

    private String writePayload(JsonNode payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid payload", e);
        }
    }
}
