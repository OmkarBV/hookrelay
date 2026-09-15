package io.hookrelay.dispatcher.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.hookrelay.common.crypto.SecretEncryptionService;
import io.hookrelay.common.delivery.Delivery;
import io.hookrelay.common.delivery.DeliveryAttempt;
import io.hookrelay.common.delivery.DeliveryRepository;
import io.hookrelay.common.delivery.DeliveryStatus;
import io.hookrelay.common.endpoint.Endpoint;
import io.hookrelay.common.endpoint.EndpointSecret;
import io.hookrelay.common.endpoint.EndpointSecretRepository;
import io.hookrelay.common.endpoint.EndpointStatus;
import io.hookrelay.common.security.EndpointUrlValidator;
import io.hookrelay.common.security.SsrfViolationException;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Executes a single delivery attempt end to end: validate, sign, send,
 * classify, record.
 *
 * <p>This runs directly on the Kafka listener's (virtual) thread rather than
 * being handed off to a separate executor — see application.yml's
 * {@code spring.threads.virtual.enabled}. That keeps each partition's
 * messages processed strictly one at a time, in order, which is the actual
 * mechanism behind per-endpoint ordering (the partition key alone only
 * guarantees messages for one endpoint land in one partition; ordering
 * requires that partition to be consumed sequentially, not fanned out). The
 * per-endpoint Resilience4j bulkhead still matters despite that: different
 * endpoints can hash to the same partition (head-of-line blocking each
 * other is an accepted trade-off of key-based partitioning), and a retried
 * delivery for the same endpoint can be picked up by a different
 * partition-worker or dispatcher instance later — the bulkhead is what caps
 * concurrent attempts at one endpoint across those cases, not within a
 * single partition's normal sequential flow.
 */
@Service
public class DeliveryExecutionService {

    private static final Logger log = LoggerFactory.getLogger(DeliveryExecutionService.class);

    private final DeliveryRepository deliveryRepository;
    private final EndpointSecretRepository endpointSecretRepository;
    private final SecretEncryptionService secretEncryptionService;
    private final SignatureService signatureService;
    private final HttpDeliveryClient httpDeliveryClient;
    private final Semaphore inFlightDeliverySemaphore;
    private final BulkheadRegistry bulkheadRegistry;
    private final DeliveryOutcomeRecorder outcomeRecorder;
    private final ObjectMapper objectMapper;
    private final EndpointUrlValidator endpointUrlValidator;

    public DeliveryExecutionService(
            DeliveryRepository deliveryRepository,
            EndpointSecretRepository endpointSecretRepository,
            SecretEncryptionService secretEncryptionService,
            SignatureService signatureService,
            HttpDeliveryClient httpDeliveryClient,
            Semaphore inFlightDeliverySemaphore,
            BulkheadRegistry bulkheadRegistry,
            DeliveryOutcomeRecorder outcomeRecorder,
            ObjectMapper objectMapper,
            EndpointUrlValidator endpointUrlValidator) {
        this.deliveryRepository = deliveryRepository;
        this.endpointSecretRepository = endpointSecretRepository;
        this.secretEncryptionService = secretEncryptionService;
        this.signatureService = signatureService;
        this.httpDeliveryClient = httpDeliveryClient;
        this.inFlightDeliverySemaphore = inFlightDeliverySemaphore;
        this.bulkheadRegistry = bulkheadRegistry;
        this.outcomeRecorder = outcomeRecorder;
        this.objectMapper = objectMapper;
        this.endpointUrlValidator = endpointUrlValidator;
    }

    public void execute(UUID deliveryId) {
        Delivery delivery = deliveryRepository.findByIdWithEndpointAndEvent(deliveryId).orElse(null);
        if (delivery == null) {
            log.warn("Delivery {} not found; skipping (endpoint or event may have been deleted)", deliveryId);
            return;
        }
        // At-least-once Kafka delivery means this task can arrive more than
        // once; a delivery already in a terminal state is a no-op, not an
        // error.
        if (delivery.getStatus() == DeliveryStatus.SUCCEEDED || delivery.getStatus() == DeliveryStatus.EXHAUSTED) {
            return;
        }

        Endpoint endpoint = delivery.getEndpoint();
        if (endpoint.getStatus() != EndpointStatus.ACTIVE) {
            log.info("Skipping delivery {}: endpoint {} is {}", deliveryId, endpoint.getId(), endpoint.getStatus());
            return;
        }

        try {
            endpointUrlValidator.validate(endpoint.getUrl());
        } catch (SsrfViolationException e) {
            recordAndTransition(delivery, null, null, null, "SSRF_BLOCKED", false, e.getMessage());
            return;
        } catch (UnknownHostException e) {
            recordAndTransition(delivery, null, null, null, "DNS_ERROR", true, e.getMessage());
            return;
        }

        List<String> signingSecrets = endpointSecretRepository.findSigningSecrets(endpoint.getId()).stream()
                .map(EndpointSecret::getSecretCiphertext)
                .map(secretEncryptionService::decrypt)
                .toList();
        if (signingSecrets.isEmpty()) {
            recordAndTransition(delivery, null, null, null, "NO_SIGNING_SECRET", false,
                    "Endpoint has no active signing secret");
            return;
        }

        Bulkhead bulkhead = bulkheadRegistry.bulkhead(endpoint.getId().toString());
        boolean semaphoreAcquired = false;
        boolean bulkheadAcquired = false;
        try {
            semaphoreAcquired = inFlightDeliverySemaphore.tryAcquire(30, TimeUnit.SECONDS);
            if (!semaphoreAcquired) {
                recordAndTransition(delivery, null, null, null, "CAPACITY_EXCEEDED", true,
                        "Global in-flight delivery limit reached");
                return;
            }
            bulkheadAcquired = bulkhead.tryAcquirePermission();
            if (!bulkheadAcquired) {
                recordAndTransition(delivery, null, null, null, "ENDPOINT_CAPACITY_EXCEEDED", true,
                        "Per-endpoint concurrency limit reached");
                return;
            }

            attemptDelivery(delivery, endpoint, signingSecrets);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (bulkheadAcquired) {
                bulkhead.releasePermission();
            }
            if (semaphoreAcquired) {
                inFlightDeliverySemaphore.release();
            }
        }
    }

    private void attemptDelivery(Delivery delivery, Endpoint endpoint, List<String> signingSecrets) {
        String rawBody = delivery.getEvent().getPayload();
        long timestamp = Instant.now().getEpochSecond();

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Hookrelay-Id", delivery.getId().toString());
        headers.put("Hookrelay-Timestamp", Long.toString(timestamp));
        headers.put("Hookrelay-Signature", signatureService.buildSignatureHeader(rawBody, timestamp, signingSecrets));

        DeliveryHttpResult result = httpDeliveryClient.send(endpoint.getUrl(), headers, rawBody, endpoint.getTimeoutMs());
        String requestHeadersJson = writeHeadersJson(headers);

        if (result.isNetworkError()) {
            recordAndTransition(delivery, requestHeadersJson, null, result.latencyMs(), result.errorType(), true,
                    "Network error: " + result.errorType());
            return;
        }

        int status = result.statusCode();
        if (status >= 200 && status < 300) {
            recordSuccess(delivery, requestHeadersJson, result);
            return;
        }

        // 429 is the one 4xx that means "try again," not "this request is
        // wrong": the receiver is explicitly asking to be retried, just not
        // right now. Every other 4xx means the receiver looked at this exact
        // request and rejected it — retrying identically will get the same
        // rejection every time, so it's terminal. Recovering from it needs
        // the customer to fix something on their end and use Replay (Phase
        // 6), not blind retry. 5xx and network errors are the receiver (or
        // the network) failing independently of what we sent, which is
        // exactly the transient condition retries are meant to recover from.
        boolean retryable = status == 429 || status >= 500;
        recordAndTransition(delivery, requestHeadersJson, status, result.latencyMs(),
                retryable ? "SERVER_ERROR" : "CLIENT_ERROR", retryable, "HTTP " + status);
    }

    private void recordSuccess(Delivery delivery, String requestHeadersJson, DeliveryHttpResult result) {
        int attemptNumber = delivery.getAttemptCount() + 1;
        DeliveryAttempt attempt = DeliveryAttempt.success(
                delivery, attemptNumber, requestHeadersJson, result.statusCode(),
                result.responseBodyTruncated(), result.latencyMs());

        delivery.setAttemptCount(attemptNumber);
        delivery.setStatus(DeliveryStatus.SUCCEEDED);
        delivery.setCompletedAt(Instant.now());
        delivery.setLastError(null);

        outcomeRecorder.record(delivery, attempt);
    }

    /**
     * Handles both real HTTP outcomes and pre-flight failures (SSRF block,
     * DNS failure, no secret, capacity exceeded) where no request was ever
     * sent — {@code responseStatus} and {@code requestHeadersJson} are null
     * in those cases, which is a truthful record of what happened, not a
     * missing value.
     */
    private void recordAndTransition(
            Delivery delivery, String requestHeadersJson, Integer responseStatus, Integer latencyMs,
            String errorType, boolean retryable, String errorMessage) {
        int attemptNumber = delivery.getAttemptCount() + 1;
        DeliveryAttempt attempt = DeliveryAttempt.failure(
                delivery, attemptNumber, requestHeadersJson, responseStatus, null, latencyMs, errorType);

        delivery.setAttemptCount(attemptNumber);
        delivery.setLastError(errorMessage);

        if (!retryable) {
            delivery.setStatus(DeliveryStatus.EXHAUSTED);
            delivery.setCompletedAt(Instant.now());
        } else {
            RetryBackoff.nextAttemptAt(attemptNumber).ifPresentOrElse(
                    nextAttemptAt -> {
                        delivery.setStatus(DeliveryStatus.FAILED);
                        delivery.setNextAttemptAt(nextAttemptAt);
                    },
                    () -> {
                        delivery.setStatus(DeliveryStatus.EXHAUSTED);
                        delivery.setCompletedAt(Instant.now());
                    });
        }

        outcomeRecorder.record(delivery, attempt);
    }

    private String writeHeadersJson(Map<String, String> headers) {
        try {
            return objectMapper.writeValueAsString(headers);
        } catch (Exception e) {
            return null;
        }
    }
}
