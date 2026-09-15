package io.hookrelay.common.delivery;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeliveryAttemptRepository extends JpaRepository<DeliveryAttempt, UUID> {
}
