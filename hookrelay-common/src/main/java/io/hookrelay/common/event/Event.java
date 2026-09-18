package io.hookrelay.common.event;

import io.hookrelay.common.application.Application;
import io.hookrelay.common.tenant.Tenant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/**
 * An ingested event, stored exactly as received. {@code payload} is kept as
 * raw JSON text (JSONB in Postgres) rather than mapped to a Java tree/POJO —
 * Hookrelay never needs to query inside a customer's payload, only store and
 * replay it verbatim, so there is nothing to gain from a richer mapping.
 */
@Entity
@Table(name = "event")
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
public class Event {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "application_id", nullable = false, updatable = false)
    private Application application;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false, updatable = false)
    private Tenant tenant;

    @Column(name = "event_type", nullable = false, updatable = false)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "jsonb", nullable = false, updatable = false)
    private String payload;

    @Column(name = "idempotency_key", updatable = false)
    private String idempotencyKey;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @Column(name = "payload_size_bytes", nullable = false, updatable = false)
    private int payloadSizeBytes;

    @Column(name = "correlation_id", nullable = false, updatable = false)
    private String correlationId;

    protected Event() {
        // JPA
    }

    /** Generates its own correlation id — for callers with no request-scoped one to thread through (mainly tests). */
    public Event(Application application, String eventType, String payload, String idempotencyKey, int payloadSizeBytes) {
        this(application, eventType, payload, idempotencyKey, payloadSizeBytes, UUID.randomUUID().toString());
    }

    public Event(
            Application application, String eventType, String payload, String idempotencyKey,
            int payloadSizeBytes, String correlationId) {
        this.application = application;
        this.tenant = application.getTenant();
        this.eventType = eventType;
        this.payload = payload;
        this.idempotencyKey = idempotencyKey;
        this.receivedAt = Instant.now();
        this.payloadSizeBytes = payloadSizeBytes;
        this.correlationId = correlationId;
    }

    public UUID getId() {
        return id;
    }

    public Application getApplication() {
        return application;
    }

    public Tenant getTenant() {
        return tenant;
    }

    public String getEventType() {
        return eventType;
    }

    public String getPayload() {
        return payload;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public int getPayloadSizeBytes() {
        return payloadSizeBytes;
    }

    public String getCorrelationId() {
        return correlationId;
    }
}
