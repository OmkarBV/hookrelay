package io.hookrelay.api.admin.application;

import io.hookrelay.common.application.Application;
import jakarta.validation.constraints.NotBlank;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/applications")
public class ApplicationController {

    private final ApplicationAdminService applicationAdminService;

    public ApplicationController(ApplicationAdminService applicationAdminService) {
        this.applicationAdminService = applicationAdminService;
    }

    public record CreateApplicationRequest(@NotBlank String name) {
    }

    public record ApplicationResponse(UUID id, String name, Instant createdAt) {
        static ApplicationResponse from(Application application) {
            return new ApplicationResponse(application.getId(), application.getName(), application.getCreatedAt());
        }
    }

    public record ApiKeyResponse(String key) {
    }

    @PostMapping
    public ResponseEntity<ApplicationResponse> create(@jakarta.validation.Valid @RequestBody CreateApplicationRequest request) {
        Application application = applicationAdminService.create(request.name());
        return ResponseEntity.created(URI.create("/api/v1/admin/applications/" + application.getId()))
                .body(ApplicationResponse.from(application));
    }

    @PostMapping("/{id}/api-keys")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiKeyResponse issueApiKey(@PathVariable UUID id) {
        return new ApiKeyResponse(applicationAdminService.issueApiKey(id));
    }
}
