package io.hookrelay.api.security.jwt;

import io.hookrelay.api.security.tenant.TenantAware;
import io.hookrelay.common.admin.AdminRole;
import java.util.UUID;

public record AdminPrincipal(UUID adminUserId, UUID tenantId, AdminRole role) implements TenantAware {

    @Override
    public UUID getTenantId() {
        return tenantId;
    }
}
