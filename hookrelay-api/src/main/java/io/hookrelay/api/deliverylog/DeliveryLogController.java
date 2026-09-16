package io.hookrelay.api.deliverylog;

import io.hookrelay.common.delivery.Delivery;
import io.hookrelay.common.delivery.DeliveryAttempt;
import io.hookrelay.common.delivery.DeliveryAttemptRepository;
import io.hookrelay.common.delivery.DeliveryRepository;
import io.hookrelay.common.delivery.DeliveryStatus;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lives under /api/v1/admin, not the bare /api/v1/deliveries the phase
 * description names — every other admin-facing resource (bootstrap, auth,
 * applications, endpoints) is under /api/v1/admin, which is exactly the
 * path SecurityConfig's admin SecurityFilterChain matches. A bare
 * /api/v1/deliveries path wouldn't be covered by either SecurityFilterChain
 * (it matches neither /api/v1/admin/** nor /api/v1/events/**) and would
 * fall through to the default chain's denyAll(), so it's kept consistent
 * with the rest of the admin surface instead.
 */
@RestController
@RequestMapping("/api/v1/admin/deliveries")
public class DeliveryLogController {

    private final DeliverySearchService deliverySearchService;
    private final DeliveryReplayService deliveryReplayService;
    private final DeliveryRepository deliveryRepository;
    private final DeliveryAttemptRepository deliveryAttemptRepository;

    public DeliveryLogController(
            DeliverySearchService deliverySearchService,
            DeliveryReplayService deliveryReplayService,
            DeliveryRepository deliveryRepository,
            DeliveryAttemptRepository deliveryAttemptRepository) {
        this.deliverySearchService = deliverySearchService;
        this.deliveryReplayService = deliveryReplayService;
        this.deliveryRepository = deliveryRepository;
        this.deliveryAttemptRepository = deliveryAttemptRepository;
    }

    @GetMapping
    public DeliveryListResponse search(
            @RequestParam(required = false) UUID endpointId,
            @RequestParam(required = false) DeliveryStatus status,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit) {
        int boundedLimit = Math.min(Math.max(limit, 1), 200);
        return deliverySearchService.search(endpointId, status, eventType, from, to, cursor, boundedLimit);
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public DeliveryDetailResponse getById(@PathVariable UUID id) {
        Delivery delivery = deliveryRepository.findByIdWithEndpointAndEvent(id)
                .orElseThrow(() -> new DeliveryNotFoundException(id));
        List<DeliveryAttempt> attempts = deliveryAttemptRepository.findByDeliveryIdOrderByAttemptedAtAsc(id);
        return DeliveryDetailResponse.from(delivery, attempts);
    }

    public record ReplayResponse(UUID newDeliveryId) {
    }

    @PostMapping("/{id}/replay")
    @PreAuthorize("hasAuthority('DELIVERY_REPLAY')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ReplayResponse replayOne(@PathVariable UUID id) {
        return new ReplayResponse(deliveryReplayService.replayOne(id));
    }

    public record BulkReplayResponse(int replayedCount, List<UUID> newDeliveryIds) {
    }

    @PostMapping("/replay")
    @PreAuthorize("hasAuthority('DELIVERY_REPLAY')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public BulkReplayResponse replayMatching(@Valid @RequestBody BulkReplayRequest request) {
        var result = deliveryReplayService.replayMatching(
                request.endpointId(), request.status(), request.eventType(), request.from(), request.to(), request.confirm());
        return new BulkReplayResponse(result.newDeliveryIds().size(), result.newDeliveryIds());
    }
}
