package io.hookrelay.common.event;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventRepository extends JpaRepository<Event, UUID> {

    Optional<Event> findByApplicationIdAndIdempotencyKey(UUID applicationId, String idempotencyKey);
}
