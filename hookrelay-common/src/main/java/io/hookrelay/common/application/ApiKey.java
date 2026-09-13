package io.hookrelay.common.application;

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
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.UuidGenerator;

/**
 * An ingestion credential. Only {@link #keyHash} is ever stored — the raw key
 * is generated, returned once to the caller, and never persisted or logged.
 * A single application can hold several ACTIVE keys at once so a key can be
 * rotated without a delivery gap.
 */
@Entity
@Table(name = "api_key")
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
public class ApiKey {

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

    @Column(name = "key_hash", nullable = false, updatable = false)
    private String keyHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ApiKeyStatus status = ApiKeyStatus.ACTIVE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected ApiKey() {
        // JPA
    }

    public ApiKey(Application application, String keyHash) {
        this.application = application;
        this.tenant = application.getTenant();
        this.keyHash = keyHash;
        this.createdAt = Instant.now();
    }

    public void revoke() {
        this.status = ApiKeyStatus.REVOKED;
        this.revokedAt = Instant.now();
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

    public String getKeyHash() {
        return keyHash;
    }

    public ApiKeyStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }
}
