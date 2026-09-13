package io.hookrelay.api.admin.bootstrap;

import io.hookrelay.common.admin.AdminUser;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/bootstrap")
public class BootstrapController {

    private final BootstrapService bootstrapService;

    public BootstrapController(BootstrapService bootstrapService) {
        this.bootstrapService = bootstrapService;
    }

    public record BootstrapResponse(UUID tenantId, UUID adminUserId, String email) {
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BootstrapResponse bootstrap(@Valid @RequestBody BootstrapRequest request) {
        AdminUser owner = bootstrapService.bootstrap(request.tenantName(), request.ownerEmail(), request.ownerPassword());
        return new BootstrapResponse(owner.getTenant().getId(), owner.getId(), owner.getEmail());
    }
}
