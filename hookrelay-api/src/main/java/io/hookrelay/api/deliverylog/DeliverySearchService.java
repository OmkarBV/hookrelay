package io.hookrelay.api.deliverylog;

import io.hookrelay.common.delivery.Delivery;
import io.hookrelay.common.delivery.DeliveryRepository;
import io.hookrelay.common.delivery.DeliveryStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keyset ("seek") pagination, not offset. {@code LIMIT/OFFSET n} forces
 * Postgres to walk and discard the first {@code n} matching rows on every
 * request — page 500 of a delivery log with millions of rows means scanning
 * and throwing away 500 pages' worth of index entries just to find where to
 * start, and that cost grows with how deep into the history you page,
 * exactly where an operator investigating an incident is most likely to be
 * looking. A keyset cursor instead encodes the last row seen and turns
 * "skip ahead" into a plain indexed range condition
 * ({@code (created_at, id) < (cursor_created_at, cursor_id)}), which costs
 * the same O(page size) regardless of how deep the page is — see
 * DeliverySpecifications for the predicate and the
 * {@code delivery_endpoint_id_created_at_idx} index (V6 migration) it runs
 * against.
 */
@Service
public class DeliverySearchService {

    private static final Sort SORT = Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id"));

    private final DeliveryRepository deliveryRepository;

    public DeliverySearchService(DeliveryRepository deliveryRepository) {
        this.deliveryRepository = deliveryRepository;
    }

    @Transactional(readOnly = true)
    public DeliveryListResponse search(
            UUID endpointId, DeliveryStatus status, String eventType, Instant from, Instant to,
            String cursorParam, int limit) {
        DeliveryCursor cursor = cursorParam != null ? DeliveryCursor.decode(cursorParam) : null;
        var spec = DeliverySpecifications.matching(endpointId, status, eventType, from, to, cursor);

        // findBy(...).limit(...) rather than findAll(spec, Pageable) on
        // purpose: Pageable's Page<T> always runs a COUNT query alongside
        // the content query to report a total, which this endpoint has no
        // use for and which is itself needless work on a large table.
        List<Delivery> results = deliveryRepository.findBy(spec, query -> query.sortBy(SORT).limit(limit).all());

        List<DeliveryListItemResponse> items = results.stream().map(DeliveryListItemResponse::from).toList();
        String nextCursor = results.size() == limit
                ? new DeliveryCursor(results.get(results.size() - 1).getCreatedAt(), results.get(results.size() - 1).getId()).encode()
                : null;
        return new DeliveryListResponse(items, nextCursor);
    }
}
