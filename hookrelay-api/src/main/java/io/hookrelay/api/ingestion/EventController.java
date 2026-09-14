package io.hookrelay.api.ingestion;

import io.hookrelay.api.security.apikey.IngestionPrincipal;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/events")
public class EventController {

    private final EventIngestionService eventIngestionService;

    public EventController(EventIngestionService eventIngestionService) {
        this.eventIngestionService = eventIngestionService;
    }

    public record IngestEventResponse(UUID eventId) {
    }

    @PostMapping
    public ResponseEntity<IngestEventResponse> ingest(
            @AuthenticationPrincipal IngestionPrincipal principal,
            @Valid @RequestBody IngestEventRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        var result = eventIngestionService.ingest(principal.applicationId(), request, idempotencyKey);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new IngestEventResponse(result.eventId()));
    }
}
