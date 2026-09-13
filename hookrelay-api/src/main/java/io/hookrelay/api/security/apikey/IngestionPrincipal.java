package io.hookrelay.api.security.apikey;

import io.hookrelay.api.security.tenant.TenantAware;
import java.util.UUID;

public record IngestionPrincipal(UUID applicationId, UUID tenantId) implements TenantAware {

    @Override
    public UUID getTenantId() {
        return tenantId;
    }
}
