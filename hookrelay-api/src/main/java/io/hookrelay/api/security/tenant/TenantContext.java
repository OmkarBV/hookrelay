package io.hookrelay.api.security.tenant;

import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Resolves the current tenant from Spring Security's {@code Authentication},
 * not a separate ThreadLocal — whichever authentication filter ran (API key
 * or admin JWT) already set a {@link TenantAware} principal, so this is
 * simply a typed read of that single source of truth.
 */
public final class TenantContext {

    private TenantContext() {
    }

    public static Optional<UUID> current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof TenantAware tenantAware) {
            return Optional.of(tenantAware.getTenantId());
        }
        return Optional.empty();
    }
}
