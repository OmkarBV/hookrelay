package io.hookrelay.common.delivery;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import io.hookrelay.common.tenant.Tenant;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/**
 * An append-only record of one HTTP attempt. The underlying table's real
 * primary key is the composite {@code (id, attempted_at)} — Postgres
 * requires the partition key in the PK of a partitioned table — but that is
 * never surfaced to JPA: every row is inserted once and never looked up by
 * id afterwards (only queried by delivery_id or time range), so mapping
 * {@code id} alone as the JPA {@code @Id} is sufficient and avoids an
 * {@code @IdClass}/{@code @EmbeddedId} for no benefit.
 */
@Entity
@Table(name = "delivery_attempt")
public class DeliveryAttempt {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id")
    private UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "delivery_id", nullable = false, updatable = false)
    private Delivery delivery;

    @ManyToOne(optional = false)
    @JoinColumn(name = "tenant_id", nullable = false, updatable = false)
    private Tenant tenant;

    @Column(name = "attempt_number", nullable = false, updatable = false)
    private int attemptNumber;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request_headers", columnDefinition = "jsonb", updatable = false)
    private String requestHeaders;

    @Column(name = "response_status", updatable = false)
    private Integer responseStatus;

    @Column(name = "response_body_truncated", updatable = false)
    private String responseBodyTruncated;

    @Column(name = "latency_ms", updatable = false)
    private Integer latencyMs;

    @Column(name = "error_type", updatable = false)
    private String errorType;

    @Column(name = "attempted_at", nullable = false, updatable = false)
    private Instant attemptedAt;

    protected DeliveryAttempt() {
        // JPA
    }

    private DeliveryAttempt(Delivery delivery, int attemptNumber) {
        this.delivery = delivery;
        this.tenant = delivery.getTenant();
        this.attemptNumber = attemptNumber;
        this.attemptedAt = Instant.now();
    }

    public static DeliveryAttempt success(
            Delivery delivery, int attemptNumber, String requestHeaders, int responseStatus,
            String responseBodyTruncated, int latencyMs) {
        DeliveryAttempt attempt = new DeliveryAttempt(delivery, attemptNumber);
        attempt.requestHeaders = requestHeaders;
        attempt.responseStatus = responseStatus;
        attempt.responseBodyTruncated = responseBodyTruncated;
        attempt.latencyMs = latencyMs;
        return attempt;
    }

    public static DeliveryAttempt failure(
            Delivery delivery, int attemptNumber, String requestHeaders, Integer responseStatus,
            String responseBodyTruncated, Integer latencyMs, String errorType) {
        DeliveryAttempt attempt = new DeliveryAttempt(delivery, attemptNumber);
        attempt.requestHeaders = requestHeaders;
        attempt.responseStatus = responseStatus;
        attempt.responseBodyTruncated = responseBodyTruncated;
        attempt.latencyMs = latencyMs;
        attempt.errorType = errorType;
        return attempt;
    }

    public UUID getId() {
        return id;
    }

    public Delivery getDelivery() {
        return delivery;
    }

    public Tenant getTenant() {
        return tenant;
    }

    public int getAttemptNumber() {
        return attemptNumber;
    }

    public String getRequestHeaders() {
        return requestHeaders;
    }

    public Integer getResponseStatus() {
        return responseStatus;
    }

    public String getResponseBodyTruncated() {
        return responseBodyTruncated;
    }

    public Integer getLatencyMs() {
        return latencyMs;
    }

    public String getErrorType() {
        return errorType;
    }

    public Instant getAttemptedAt() {
        return attemptedAt;
    }
}
