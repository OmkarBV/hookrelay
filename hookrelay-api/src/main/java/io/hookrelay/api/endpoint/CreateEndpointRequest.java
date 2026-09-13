package io.hookrelay.api.endpoint;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

public record CreateEndpointRequest(
        @NotNull UUID applicationId,
        @NotBlank String url,
        String description,
        @NotEmpty List<String> eventTypes) {
}
