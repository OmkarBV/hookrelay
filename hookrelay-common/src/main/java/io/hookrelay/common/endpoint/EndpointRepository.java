package io.hookrelay.common.endpoint;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EndpointRepository extends JpaRepository<Endpoint, UUID> {

    @Query("""
            select e from Endpoint e join e.subscribedEventTypes t
            where e.application.id = :applicationId and e.status = :status and t = :eventType
            """)
    List<Endpoint> findSubscribed(
            @Param("applicationId") UUID applicationId,
            @Param("status") EndpointStatus status,
            @Param("eventType") String eventType);
}
