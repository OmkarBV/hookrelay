package io.hookrelay.api.endpoint;

import io.hookrelay.common.endpoint.Endpoint;
import io.hookrelay.common.endpoint.EndpointStatus;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record EndpointResponse(
        UUID id,
        UUID applicationId,
        String url,
        String description,
        EndpointStatus status,
        Set<String> eventTypes,
        Instant createdAt,
        String secret) {

    /** {@code secret} is non-null only on the create response — shown once, never again. */
    public static EndpointResponse from(Endpoint endpoint, String rawSecret) {
        return new EndpointResponse(
                endpoint.getId(),
                endpoint.getApplication().getId(),
                endpoint.getUrl(),
                endpoint.getDescription(),
                endpoint.getStatus(),
                endpoint.getSubscribedEventTypes(),
                endpoint.getCreatedAt(),
                rawSecret);
    }

    public static EndpointResponse from(Endpoint endpoint) {
        return from(endpoint, null);
    }
}
