package io.hookrelay.api.deliverylog;

import io.hookrelay.common.delivery.Delivery;
import io.hookrelay.common.delivery.DeliveryAttempt;
import java.util.List;

public record DeliveryDetailResponse(DeliveryListItemResponse delivery, List<DeliveryAttemptResponse> attempts) {

    static DeliveryDetailResponse from(Delivery delivery, List<DeliveryAttempt> attempts) {
        return new DeliveryDetailResponse(
                DeliveryListItemResponse.from(delivery),
                attempts.stream().map(DeliveryAttemptResponse::from).toList());
    }
}
