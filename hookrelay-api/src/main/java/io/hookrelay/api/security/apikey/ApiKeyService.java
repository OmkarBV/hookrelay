package io.hookrelay.api.security.apikey;

import io.hookrelay.common.application.ApiKey;
import io.hookrelay.common.application.ApiKeyRepository;
import io.hookrelay.common.application.ApiKeyStatus;
import io.hookrelay.common.application.Application;
import io.hookrelay.common.application.ApplicationStatus;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ApiKeyService {

    private final ApiKeyRepository apiKeyRepository;

    public ApiKeyService(ApiKeyRepository apiKeyRepository) {
        this.apiKeyRepository = apiKeyRepository;
    }

    /** Returns the raw key. It is never stored or logged, and cannot be retrieved again. */
    @Transactional
    public String issue(Application application) {
        String rawKey = ApiKeyGenerator.generate();
        apiKeyRepository.save(new ApiKey(application, ApiKeyGenerator.hash(rawKey)));
        return rawKey;
    }

    /**
     * Looks up the application for a raw key presented on an ingestion request.
     * Runs before any tenant is known, so it is intentionally not tenant-filtered
     * — that would be circular, since resolving the tenant is the point of this
     * call.
     */
    @Transactional(readOnly = true)
    public Optional<Application> authenticate(String rawKey) {
        String hash = ApiKeyGenerator.hash(rawKey);
        return apiKeyRepository.findByKeyHashAndStatus(hash, ApiKeyStatus.ACTIVE)
                .map(ApiKey::getApplication)
                .filter(application -> application.getStatus() == ApplicationStatus.ACTIVE);
    }
}
