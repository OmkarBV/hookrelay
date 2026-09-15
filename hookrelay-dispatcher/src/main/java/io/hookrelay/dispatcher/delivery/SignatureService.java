package io.hookrelay.dispatcher.delivery;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Builds the {@code Hookrelay-Signature} header following the Stripe/Svix
 * model: {@code t=<unix_ts>,v1=<hmac>[,v1=<hmac>...]}.
 *
 * <p>The signed string is {@code <timestamp>.<raw_body>}, not the body
 * alone — binding the timestamp into the signature is what lets a receiver
 * reject a replayed request (an attacker who captured a valid signed
 * request can't reuse it after checking the timestamp is recent; see
 * RECEIVERS.md for the receiver-side verification this enables).
 *
 * <p>One {@code v1=} value is emitted per non-retired endpoint secret. During
 * a rotation window that's two values (ACTIVE and ROTATING), so a receiver
 * that hasn't picked up the new secret yet still verifies against the old
 * one, and one that has already switched verifies against the new one —
 * neither side needs to coordinate the exact moment of cutover.
 */
@Component
public class SignatureService {

    public String buildSignatureHeader(String rawBody, long timestampEpochSeconds, List<String> secrets) {
        String signedPayload = timestampEpochSeconds + "." + rawBody;
        StringBuilder header = new StringBuilder("t=").append(timestampEpochSeconds);
        for (String secret : secrets) {
            header.append(",v1=").append(hmacSha256Hex(signedPayload, secret));
        }
        return header.toString();
    }

    private String hmacSha256Hex(String data, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("Failed to compute HMAC-SHA256 signature", e);
        }
    }
}
