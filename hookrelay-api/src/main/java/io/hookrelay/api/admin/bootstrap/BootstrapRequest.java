package io.hookrelay.api.admin.bootstrap;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record BootstrapRequest(
        @NotBlank String tenantName,
        @NotBlank @Email String ownerEmail,
        @NotBlank @Size(min = 12) String ownerPassword) {
}
