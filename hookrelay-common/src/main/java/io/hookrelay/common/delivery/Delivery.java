package io.hookrelay.common.delivery;

import io.hookrelay.common.endpoint.Endpoint;
import io.hookrelay.common.event.Event;
import io.hookrelay.common.tenant.Tenant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.UuidGenerator;

/**
 * One fan-out target for an event: a single (event, endpoint) pair the
 * dispatcher must attempt to deliver. Created PENDING with
 * {@code nextAttemptAt = now()} at ingestion time — the same row a Phase 5
 * retry sweeper will later poll for FAILED deliveries also serves as the
 * durable "outbox" entry for the very first attempt, so no separate outbox
 * table exists (see EventIngestionService for why this matters).
 */
@Entity
@Table(name = "delivery")
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
public class Delivery {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false, updatable = false)
    private Event event;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "endpoint_id", nullable = false, updatable = false)
    private Endpoint endpoint;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false, updatable = false)
    private Tenant tenant;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private DeliveryStatus status = DeliveryStatus.PENDING;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 0;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected Delivery() {
        // JPA
    }

    public Delivery(Event event, Endpoint endpoint) {
        this.event = event;
        this.endpoint = endpoint;
        this.tenant = event.getTenant();
        this.createdAt = Instant.now();
        this.nextAttemptAt = this.createdAt;
    }

    public UUID getId() {
        return id;
    }

    public Event getEvent() {
        return event;
    }

    public Endpoint getEndpoint() {
        return endpoint;
    }

    public Tenant getTenant() {
        return tenant;
    }

    public DeliveryStatus getStatus() {
        return status;
    }

    public void setStatus(DeliveryStatus status) {
        this.status = status;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public void setAttemptCount(int attemptCount) {
        this.attemptCount = attemptCount;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public void setNextAttemptAt(Instant nextAttemptAt) {
        this.nextAttemptAt = nextAttemptAt;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }
}
