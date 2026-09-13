package io.hookrelay.common.endpoint;

import io.hookrelay.common.application.Application;
import io.hookrelay.common.tenant.Tenant;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.UuidGenerator;

/** A customer's webhook receiving URL. */
@Entity
@Table(name = "endpoint")
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
public class Endpoint {

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

    @Column(name = "url", nullable = false)
    private String url;

    @Column(name = "description")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private EndpointStatus status = EndpointStatus.ACTIVE;

    @Column(name = "rate_limit_per_sec", nullable = false)
    private int rateLimitPerSec = 10;

    @Column(name = "timeout_ms", nullable = false)
    private int timeoutMs = 10_000;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "endpoint_subscription", joinColumns = @JoinColumn(name = "endpoint_id"))
    @Column(name = "event_type", nullable = false)
    private Set<String> subscribedEventTypes = new HashSet<>();

    protected Endpoint() {
        // JPA
    }

    public Endpoint(Application application, String url, String description) {
        this.application = application;
        this.tenant = application.getTenant();
        this.url = url;
        this.description = description;
        this.createdAt = Instant.now();
    }

    public void subscribeTo(String eventType) {
        subscribedEventTypes.add(eventType);
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

    public String getUrl() {
        return url;
    }

    public String getDescription() {
        return description;
    }

    public EndpointStatus getStatus() {
        return status;
    }

    public void setStatus(EndpointStatus status) {
        this.status = status;
    }

    public int getRateLimitPerSec() {
        return rateLimitPerSec;
    }

    public void setRateLimitPerSec(int rateLimitPerSec) {
        this.rateLimitPerSec = rateLimitPerSec;
    }

    public int getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Set<String> getSubscribedEventTypes() {
        return subscribedEventTypes;
    }
}
