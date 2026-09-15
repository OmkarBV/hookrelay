package io.hookrelay.api.endpoint;

import io.hookrelay.common.application.Application;
import io.hookrelay.common.application.ApplicationRepository;
import io.hookrelay.common.crypto.SecretEncryptionService;
import io.hookrelay.common.endpoint.Endpoint;
import io.hookrelay.common.endpoint.EndpointRepository;
import io.hookrelay.common.endpoint.EndpointSecret;
import io.hookrelay.common.endpoint.EndpointSecretRepository;
import io.hookrelay.common.security.EndpointUrlValidator;
import java.net.UnknownHostException;
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
    private final EndpointSecretRepository endpointSecretRepository;
    private final SecretEncryptionService secretEncryptionService;
    private final EndpointUrlValidator endpointUrlValidator;

    public EndpointService(
            EndpointRepository endpointRepository,
            ApplicationRepository applicationRepository,
            EndpointSecretRepository endpointSecretRepository,
            SecretEncryptionService secretEncryptionService,
            EndpointUrlValidator endpointUrlValidator) {
        this.endpointRepository = endpointRepository;
        this.applicationRepository = applicationRepository;
        this.endpointSecretRepository = endpointSecretRepository;
        this.secretEncryptionService = secretEncryptionService;
        this.endpointUrlValidator = endpointUrlValidator;
    }

    public record CreateResult(Endpoint endpoint, String rawSecret) {
    }

    @Transactional
    public CreateResult create(UUID applicationId, String url, String description, List<String> eventTypes) {
        try {
            endpointUrlValidator.validate(url);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Cannot resolve endpoint host: " + url);
        }

        Application application = applicationRepository.findById(applicationId)
                .orElseThrow(() -> new IllegalArgumentException("Application not found: " + applicationId));
        Endpoint endpoint = new Endpoint(application, url, description);
        eventTypes.forEach(endpoint::subscribeTo);
        endpoint = endpointRepository.save(endpoint);

        // An endpoint with no signing secret can never have a delivery
        // signed, so one is provisioned immediately rather than requiring a
        // separate setup step. Shown once in the response, like an API key.
        String rawSecret = EndpointSecretGenerator.generate();
        endpointSecretRepository.save(new EndpointSecret(endpoint, secretEncryptionService.encrypt(rawSecret)));

        return new CreateResult(endpoint, rawSecret);
    }

    @Transactional(readOnly = true)
    public Endpoint getById(UUID id) {
        return endpointRepository.findById(id).orElseThrow(() -> new EndpointNotFoundException(id));
    }
}
