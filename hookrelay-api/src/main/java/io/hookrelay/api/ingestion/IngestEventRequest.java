package io.hookrelay.api.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record IngestEventRequest(
        @NotBlank
        @Pattern(
                regexp = "^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$",
                message = "must be dot-namespaced, e.g. 'invoice.paid'")
        String eventType,
        @NotNull JsonNode payload,
        String eventId) {
}
