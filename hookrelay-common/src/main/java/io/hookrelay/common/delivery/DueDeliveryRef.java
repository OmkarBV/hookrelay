package io.hookrelay.common.delivery;

import java.util.UUID;

/**
 * A lightweight projection for the retry sweeper: just enough to republish
 * to Kafka (id and the endpoint id used as the partition key), without
 * hydrating full Delivery/Endpoint entities the sweeper doesn't otherwise
 * need.
 */
public interface DueDeliveryRef {

    UUID getId();

    UUID getEndpointId();
}
