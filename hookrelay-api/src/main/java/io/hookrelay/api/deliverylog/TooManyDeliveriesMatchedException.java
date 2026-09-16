package io.hookrelay.api.deliverylog;

public class TooManyDeliveriesMatchedException extends RuntimeException {

    public TooManyDeliveriesMatchedException(int cap) {
        super("More than " + cap + " deliveries match this filter — narrow endpointId/status/eventType/from/to "
                + "before replaying, rather than replaying in one uncontrolled batch");
    }
}
