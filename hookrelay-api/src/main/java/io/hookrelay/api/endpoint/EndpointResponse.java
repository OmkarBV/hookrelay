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
        Instant createdAt) {

    public static EndpointResponse from(Endpoint endpoint) {
        return new EndpointResponse(
                endpoint.getId(),
                endpoint.getApplication().getId(),
                endpoint.getUrl(),
                endpoint.getDescription(),
                endpoint.getStatus(),
                endpoint.getSubscribedEventTypes(),
                endpoint.getCreatedAt());
    }
}
