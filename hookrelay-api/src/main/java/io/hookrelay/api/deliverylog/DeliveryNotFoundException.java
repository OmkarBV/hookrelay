package io.hookrelay.api.deliverylog;

import java.util.UUID;

public class DeliveryNotFoundException extends RuntimeException {

    public DeliveryNotFoundException(UUID id) {
        super("Delivery not found: " + id);
    }
}
