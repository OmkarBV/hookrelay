package io.hookrelay.common.endpoint;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EndpointSecretRepository extends JpaRepository<EndpointSecret, UUID> {

    @Query("select s from EndpointSecret s where s.endpoint.id = :endpointId and s.status <> io.hookrelay.common.endpoint.SecretStatus.RETIRED")
    List<EndpointSecret> findSigningSecrets(@Param("endpointId") UUID endpointId);
}
