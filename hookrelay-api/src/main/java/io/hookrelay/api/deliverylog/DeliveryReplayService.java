package io.hookrelay.api.deliverylog;

import io.hookrelay.api.security.tenant.TenantContext;
import io.hookrelay.common.delivery.Delivery;
import io.hookrelay.common.delivery.DeliveryPublisher;
import io.hookrelay.common.delivery.DeliveryRepository;
import io.hookrelay.common.delivery.DeliveryStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A replay always creates a new Delivery row (see Delivery.replayOf) —
 * never re-runs, resets, or mutates the original. The original's status,
 * attemptCount, and every DeliveryAttempt row it accumulated stay exactly
 * as they were; only the new row is PENDING and gets a fresh
 * DeliveryPublisher.publish call, following the same commit-then-publish
 * outbox discipline as the original ingestion path (EventIngestionService)
 * and the retry sweeper — replay rows are saved and committed first, and
 * only published to Kafka afterward, outside the transaction.
 *
 * <p>Uses {@link TransactionTemplate} rather than {@code @Transactional} for
 * the same reason EventIngestionService does: the publish step has to run
 * strictly after commit, in code that isn't itself wrapped in the same
 * transaction, and a private helper method annotated {@code @Transactional}
 * called via {@code this.} would silently skip the proxy and not actually
 * open one.
 */
@Service
public class DeliveryReplayService {

    private final DeliveryRepository deliveryRepository;
    private final DeliveryPublisher deliveryPublisher;
    private final BulkReplayRateLimiters rateLimiters;
    private final TransactionTemplate transactionTemplate;
    private final int bulkReplayCap;

    public DeliveryReplayService(
            DeliveryRepository deliveryRepository,
            DeliveryPublisher deliveryPublisher,
            BulkReplayRateLimiters rateLimiters,
            PlatformTransactionManager transactionManager,
            @Value("${hookrelay.replay.bulk.max-batch-size:500}") int bulkReplayCap) {
        this.deliveryRepository = deliveryRepository;
        this.deliveryPublisher = deliveryPublisher;
        this.rateLimiters = rateLimiters;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.bulkReplayCap = bulkReplayCap;
    }

    public UUID replayOne(UUID deliveryId) {
        record NewReplay(UUID id, UUID endpointId) {
        }
        NewReplay created = transactionTemplate.execute(status -> {
            Delivery original = deliveryRepository.findById(deliveryId)
                    .orElseThrow(() -> new DeliveryNotFoundException(deliveryId));
            Delivery replay = deliveryRepository.save(Delivery.replayOf(original));
            return new NewReplay(replay.getId(), replay.getEndpoint().getId());
        });
        deliveryPublisher.publish(created.id(), created.endpointId());
        return created.id();
    }

    public record BulkReplayResult(List<UUID> newDeliveryIds) {
    }

    public BulkReplayResult replayMatching(
            UUID endpointId, DeliveryStatus status, String eventType, Instant from, Instant to, boolean confirm) {
        if (!confirm) {
            throw new BulkReplayNotConfirmedException();
        }

        UUID tenantId = TenantContext.current().orElseThrow(() -> new IllegalStateException("No authenticated tenant"));
        if (!rateLimiters.forTenant(tenantId).acquirePermission()) {
            throw new BulkReplayRateLimitExceededException();
        }

        record NewReplay(UUID id, UUID endpointId) {
        }
        List<NewReplay> created = transactionTemplate.execute(txStatus -> {
            var spec = DeliverySpecifications.matching(endpointId, status, eventType, from, to, null);
            List<Delivery> matched = deliveryRepository.findBy(spec, query -> query.limit(bulkReplayCap + 1).all());
            if (matched.size() > bulkReplayCap) {
                throw new TooManyDeliveriesMatchedException(bulkReplayCap);
            }
            return matched.stream()
                    .map(Delivery::replayOf)
                    .map(deliveryRepository::save)
                    .map(replay -> new NewReplay(replay.getId(), replay.getEndpoint().getId()))
                    .toList();
        });

        for (NewReplay replay : created) {
            deliveryPublisher.publish(replay.id(), replay.endpointId());
        }
        return new BulkReplayResult(created.stream().map(NewReplay::id).toList());
    }
}
