package io.hookrelay.dispatcher.delivery;

import io.hookrelay.common.delivery.DeliveryPublisher;
import io.hookrelay.common.delivery.DeliveryRepository;
import io.hookrelay.common.delivery.DueDeliveryRef;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Polls {@code delivery WHERE status='FAILED' AND next_attempt_at <= now()}
 * and republishes each to Kafka — the same mechanism that makes the initial
 * publish an "outbox" (see EventIngestionService) makes retries one too: the
 * durable state (a FAILED row with a due nextAttemptAt) is written first,
 * and only after that's safely committed does anything external happen.
 *
 * <p>Deliberately does NOT use ShedLock. Unlike a job that must run exactly
 * once cluster-wide (see PartitionMaintenanceJob), this one is designed to
 * run concurrently on every dispatcher instance — {@code FOR UPDATE SKIP
 * LOCKED} (see DeliveryRepository.lockDueForRetry) already makes concurrent
 * sweeps safe by having each instance grab a disjoint batch of rows, so
 * forcing only one instance to sweep would just throttle retry throughput
 * to a single instance's pace for no correctness benefit.
 */
@Component
public class RetrySweeper {

    private static final Logger log = LoggerFactory.getLogger(RetrySweeper.class);

    private final DeliveryRepository deliveryRepository;
    private final DeliveryPublisher deliveryPublisher;
    private final TransactionTemplate transactionTemplate;
    private final int batchSize;
    private final Duration republishGuard;

    public RetrySweeper(
            DeliveryRepository deliveryRepository,
            DeliveryPublisher deliveryPublisher,
            PlatformTransactionManager transactionManager,
            @Value("${hookrelay.dispatcher.sweeper.batch-size:100}") int batchSize,
            @Value("${hookrelay.dispatcher.sweeper.republish-guard-seconds:120}") long republishGuardSeconds) {
        this.deliveryRepository = deliveryRepository;
        this.deliveryPublisher = deliveryPublisher;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.batchSize = batchSize;
        this.republishGuard = Duration.ofSeconds(republishGuardSeconds);
    }

    @Scheduled(fixedDelayString = "${hookrelay.dispatcher.sweeper.interval-ms:5000}")
    public void sweep() {
        List<DueDeliveryRef> due = transactionTemplate.execute(status -> {
            List<DueDeliveryRef> locked = deliveryRepository.lockDueForRetry(batchSize);
            if (!locked.isEmpty()) {
                List<UUID> ids = locked.stream().map(DueDeliveryRef::getId).toList();
                deliveryRepository.pushNextAttemptAt(ids, Instant.now().plus(republishGuard));
            }
            return locked;
        });

        if (due == null || due.isEmpty()) {
            return;
        }
        log.debug("Retry sweeper republishing {} deliveries", due.size());
        for (DueDeliveryRef ref : due) {
            deliveryPublisher.publish(ref.getId(), ref.getEndpointId());
        }
    }
}
