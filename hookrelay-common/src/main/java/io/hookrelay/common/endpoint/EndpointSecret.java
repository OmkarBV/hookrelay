package io.hookrelay.common.endpoint;

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
 * A signing secret for an endpoint. Multiple rows can exist per endpoint —
 * during rotation one is ACTIVE and one is ROTATING, and the dispatcher signs
 * with every non-RETIRED secret (see Phase 4) so receivers can move to the
 * new key on their own schedule. Uniqueness of "at most one ACTIVE / one
 * ROTATING per endpoint" is enforced by {@code EndpointSecretService}, not a
 * DB constraint, so RETIRED history can accumulate freely.
 */
@Entity
@Table(name = "endpoint_secret")
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
public class EndpointSecret {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "endpoint_id", nullable = false, updatable = false)
    private Endpoint endpoint;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false, updatable = false)
    private Tenant tenant;

    // Encrypted (AES-256-GCM, see SecretEncryptionService), not hashed — the
    // dispatcher must recover the actual secret to sign with it. See the V4
    // migration for why this differs from api_key/admin_user's hash-only
    // storage.
    @Column(name = "secret_ciphertext", nullable = false, updatable = false)
    private String secretCiphertext;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private SecretStatus status = SecretStatus.ACTIVE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected EndpointSecret() {
        // JPA
    }

    public EndpointSecret(Endpoint endpoint, String secretCiphertext) {
        this.endpoint = endpoint;
        this.tenant = endpoint.getTenant();
        this.secretCiphertext = secretCiphertext;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public Endpoint getEndpoint() {
        return endpoint;
    }

    public Tenant getTenant() {
        return tenant;
    }

    public String getSecretCiphertext() {
        return secretCiphertext;
    }

    public SecretStatus getStatus() {
        return status;
    }

    public void setStatus(SecretStatus status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
