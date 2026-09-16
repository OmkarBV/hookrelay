package io.hookrelay.api.deliverylog;

import java.util.List;

public record DeliveryListResponse(List<DeliveryListItemResponse> items, String nextCursor) {
}
