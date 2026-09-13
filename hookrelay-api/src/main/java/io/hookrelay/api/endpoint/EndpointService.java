package io.hookrelay.api.endpoint;

import io.hookrelay.common.application.Application;
import io.hookrelay.common.application.ApplicationRepository;
import io.hookrelay.common.endpoint.Endpoint;
import io.hookrelay.common.endpoint.EndpointRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * No tenant checks appear anywhere in this class. The tenantFilter Hibernate
 * filter (enabled per-request by TenantScopingFilter) already restricts every
 * query below to the caller's tenant, so a lookup by an id belonging to a
 * different tenant behaves exactly like a lookup of an id that doesn't exist
 * — findById returns empty, and this surfaces as a 404, never a 403.
 */
@Service
public class EndpointService {

    private final EndpointRepository endpointRepository;
    private final ApplicationRepository applicationRepository;

    public EndpointService(EndpointRepository endpointRepository, ApplicationRepository applicationRepository) {
        this.endpointRepository = endpointRepository;
        this.applicationRepository = applicationRepository;
    }

    @Transactional
    public Endpoint create(UUID applicationId, String url, String description, List<String> eventTypes) {
        Application application = applicationRepository.findById(applicationId)
                .orElseThrow(() -> new IllegalArgumentException("Application not found: " + applicationId));
        Endpoint endpoint = new Endpoint(application, url, description);
        eventTypes.forEach(endpoint::subscribeTo);
        return endpointRepository.save(endpoint);
    }

    @Transactional(readOnly = true)
    public Endpoint getById(UUID id) {
        return endpointRepository.findById(id).orElseThrow(() -> new EndpointNotFoundException(id));
    }
}
