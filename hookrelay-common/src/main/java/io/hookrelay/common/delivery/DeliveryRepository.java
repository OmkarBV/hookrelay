package io.hookrelay.common.delivery;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DeliveryRepository extends JpaRepository<Delivery, UUID>, JpaSpecificationExecutor<Delivery> {

    /**
     * Fetch-joins endpoint, event, and tenant so all three are safely
     * accessible after this (short, read-only) transaction closes —
     * Delivery.endpoint/event/tenant are all lazy, and the dispatcher
     * deliberately does not hold a transaction open across the HTTP call
     * that follows loading a delivery (tenant is needed to stamp
     * DeliveryAttempt.tenant when recording the outcome afterward).
     */
    @Query("select d from Delivery d join fetch d.endpoint join fetch d.event join fetch d.tenant where d.id = :id")
    Optional<Delivery> findByIdWithEndpointAndEvent(@Param("id") UUID id);

    /**
     * The retry sweeper's core query. {@code FOR UPDATE SKIP LOCKED} is what
     * makes it safe for multiple dispatcher instances to run this
     * concurrently: each instance's query locks whatever rows it selects and
     * silently skips any row another instance already has locked, instead of
     * blocking on it (plain {@code FOR UPDATE}) or double-selecting it (no
     * locking at all). Every instance grabs a disjoint batch and no delivery
     * is ever swept twice at once — no coordination between instances
     * required beyond what Postgres's row locks already provide.
     */
    @Query(
            value = """
                    select id, endpoint_id as endpointId
                    from delivery
                    where status = 'FAILED' and next_attempt_at <= now()
                    order by next_attempt_at
                    limit :batchSize
                    for update skip locked
                    """,
            nativeQuery = true)
    List<DueDeliveryRef> lockDueForRetry(@Param("batchSize") int batchSize);

    /**
     * Pushes the locked rows' nextAttemptAt out by a guard window before
     * they're republished (see RetrySweeper) — not a status change, so a
     * crash between this commit and the Kafka publish simply means the next
     * sweep picks the row up again once the guard window elapses, rather
     * than leaving it stuck.
     */
    @Modifying
    @Query("update Delivery d set d.nextAttemptAt = :guardedUntil where d.id in :ids")
    void pushNextAttemptAt(@Param("ids") Collection<UUID> ids, @Param("guardedUntil") Instant guardedUntil);
}
