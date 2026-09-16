package io.hookrelay.dispatcher.delivery;

import io.hookrelay.common.endpoint.EndpointRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EndpointPauseService {

    private static final Logger log = LoggerFactory.getLogger(EndpointPauseService.class);

    private final EndpointRepository endpointRepository;

    public EndpointPauseService(EndpointRepository endpointRepository) {
        this.endpointRepository = endpointRepository;
    }

    @Transactional
    public void autoPause(UUID endpointId, String reason) {
        endpointRepository.findById(endpointId).ifPresent(endpoint -> {
            endpoint.pause(reason);
            endpointRepository.save(endpoint);
            log.warn("Auto-paused endpoint {}: {}", endpointId, reason);
        });
    }
}
