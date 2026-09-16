package io.hookrelay.api.deliverylog;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * A keyset pagination cursor: opaque to the client, but really just the
 * (createdAt, id) of the last row on the previous page — the tiebreaker on
 * id is needed because createdAt alone isn't unique enough to guarantee a
 * stable order when two deliveries are created in the same instant.
 */
record DeliveryCursor(Instant createdAt, UUID id) {

    String encode() {
        String raw = createdAt.toEpochMilli() + ":" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    static DeliveryCursor decode(String encoded) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            int separator = raw.indexOf(':');
            long epochMilli = Long.parseLong(raw.substring(0, separator));
            UUID id = UUID.fromString(raw.substring(separator + 1));
            return new DeliveryCursor(Instant.ofEpochMilli(epochMilli), id);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid cursor");
        }
    }
}
