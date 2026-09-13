package io.hookrelay.common.endpoint;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EndpointRepository extends JpaRepository<Endpoint, UUID> {
}
