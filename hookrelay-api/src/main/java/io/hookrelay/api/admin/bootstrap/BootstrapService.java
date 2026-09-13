package io.hookrelay.api.admin.bootstrap;

import io.hookrelay.common.admin.AdminRole;
import io.hookrelay.common.admin.AdminUser;
import io.hookrelay.common.admin.AdminUserRepository;
import io.hookrelay.common.tenant.Tenant;
import io.hookrelay.common.tenant.TenantRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hookrelay is self-hosted with no public sign-up flow, so the very first
 * tenant and its OWNER admin have to come from somewhere other than an
 * authenticated admin endpoint (there is no admin yet to authenticate as).
 * This endpoint is intentionally unauthenticated but only ever succeeds
 * once: as soon as any tenant exists, it refuses. Operators run it manually
 * right after first deploy, the same way many self-hosted tools handle
 * first-run setup.
 */
@Service
public class BootstrapService {

    private final TenantRepository tenantRepository;
    private final AdminUserRepository adminUserRepository;
    private final PasswordEncoder passwordEncoder;

    public BootstrapService(
            TenantRepository tenantRepository,
            AdminUserRepository adminUserRepository,
            PasswordEncoder passwordEncoder) {
        this.tenantRepository = tenantRepository;
        this.adminUserRepository = adminUserRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public AdminUser bootstrap(String tenantName, String ownerEmail, String ownerPassword) {
        if (tenantRepository.count() > 0) {
            throw new IllegalStateException("Bootstrap already completed; create tenants via an OWNER admin instead");
        }
        Tenant tenant = tenantRepository.save(new Tenant(tenantName));
        return adminUserRepository.save(
                new AdminUser(tenant, ownerEmail, passwordEncoder.encode(ownerPassword), AdminRole.OWNER));
    }
}
