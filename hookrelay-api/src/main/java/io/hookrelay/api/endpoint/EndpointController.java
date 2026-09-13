package io.hookrelay.api.endpoint;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/endpoints")
public class EndpointController {

    private final EndpointService endpointService;

    public EndpointController(EndpointService endpointService) {
        this.endpointService = endpointService;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('ENDPOINT_CREATE')")
    public ResponseEntity<EndpointResponse> create(@Valid @RequestBody CreateEndpointRequest request) {
        var endpoint = endpointService.create(
                request.applicationId(), request.url(), request.description(), request.eventTypes());
        return ResponseEntity.created(URI.create("/api/v1/admin/endpoints/" + endpoint.getId()))
                .body(EndpointResponse.from(endpoint));
    }

    @GetMapping("/{id}")
    public EndpointResponse getById(@PathVariable UUID id) {
        return EndpointResponse.from(endpointService.getById(id));
    }
}
