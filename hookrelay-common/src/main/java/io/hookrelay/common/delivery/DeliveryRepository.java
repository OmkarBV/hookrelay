package io.hookrelay.common.delivery;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DeliveryRepository extends JpaRepository<Delivery, UUID> {

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
}
