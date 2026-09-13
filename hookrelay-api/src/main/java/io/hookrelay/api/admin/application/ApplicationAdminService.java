package io.hookrelay.api.admin.application;

import io.hookrelay.api.security.apikey.ApiKeyService;
import io.hookrelay.api.security.tenant.TenantContext;
import io.hookrelay.common.application.Application;
import io.hookrelay.common.application.ApplicationRepository;
import io.hookrelay.common.tenant.TenantRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ApplicationAdminService {

    private final ApplicationRepository applicationRepository;
    private final TenantRepository tenantRepository;
    private final ApiKeyService apiKeyService;

    public ApplicationAdminService(
            ApplicationRepository applicationRepository,
            TenantRepository tenantRepository,
            ApiKeyService apiKeyService) {
        this.applicationRepository = applicationRepository;
        this.tenantRepository = tenantRepository;
        this.apiKeyService = apiKeyService;
    }

    @Transactional
    public Application create(String name) {
        UUID tenantId = TenantContext.current()
                .orElseThrow(() -> new IllegalStateException("No authenticated tenant"));
        var tenant = tenantRepository.getReferenceById(tenantId);
        return applicationRepository.save(new Application(tenant, name));
    }

    @Transactional(readOnly = true)
    public Application getById(UUID applicationId) {
        return applicationRepository.findById(applicationId)
                .orElseThrow(() -> new IllegalArgumentException("Application not found: " + applicationId));
    }

    /** Returns the raw key. It is shown to the caller exactly once. */
    @Transactional
    public String issueApiKey(UUID applicationId) {
        return apiKeyService.issue(getById(applicationId));
    }
}
