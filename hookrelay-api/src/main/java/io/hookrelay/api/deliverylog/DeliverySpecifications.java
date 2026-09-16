package io.hookrelay.api.deliverylog;

import io.hookrelay.common.delivery.Delivery;
import io.hookrelay.common.delivery.DeliveryStatus;
import io.hookrelay.common.event.Event;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/**
 * Composable Criteria predicates for the delivery search endpoint. Built as
 * a {@link Specification} rather than a handful of {@code @Query} variants
 * because every filter here is optional and independently combinable —
 * covering that with derived-query methods would mean 2^5 method
 * permutations for five optional filters.
 */
final class DeliverySpecifications {

    private DeliverySpecifications() {
    }

    static Specification<Delivery> matching(
            UUID endpointId, DeliveryStatus status, String eventType, Instant from, Instant to, DeliveryCursor cursor) {
        return (root, query, cb) -> {
            // A single join to `event`, reused both to fetch it (avoiding an
            // N+1 lazy load per row when mapping the response) and, when
            // filtering by eventType, to restrict on it — rather than a
            // separate root.join("event") that could produce a second,
            // redundant join to the same table. The count query Spring Data
            // runs alongside Pageable can't have fetches applied, hence the
            // guard.
            boolean isCountQuery = Long.class == query.getResultType() || long.class == query.getResultType();
            Join<Delivery, Event> event;
            if (isCountQuery) {
                event = root.join("event");
            } else {
                @SuppressWarnings("unchecked")
                Join<Delivery, Event> fetchedAsJoin = (Join<Delivery, Event>) (Join<?, ?>) root.fetch("event", JoinType.LEFT);
                event = fetchedAsJoin;
            }
            if (!isCountQuery) {
                root.fetch("endpoint", JoinType.LEFT);
            }

            List<Predicate> predicates = new ArrayList<>();

            if (endpointId != null) {
                predicates.add(cb.equal(root.get("endpoint").get("id"), endpointId));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (eventType != null) {
                predicates.add(cb.equal(event.get("eventType"), eventType));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), to));
            }
            if (cursor != null) {
                // (createdAt, id) < (cursor.createdAt, cursor.id) in
                // descending keyset order — the tiebreaker on id matters
                // whenever two rows share the same createdAt instant.
                Predicate strictlyOlder = cb.lessThan(root.get("createdAt"), cursor.createdAt());
                Predicate sameInstantButLowerId = cb.and(
                        cb.equal(root.get("createdAt"), cursor.createdAt()),
                        cb.lessThan(root.get("id"), cursor.id()));
                predicates.add(cb.or(strictlyOlder, sameInstantButLowerId));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
