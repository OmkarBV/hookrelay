package io.hookrelay.api.endpoint;

import java.security.SecureRandom;
import java.util.Base64;

/** Generates raw endpoint signing secrets, mirroring ApiKeyGenerator's approach to randomness. */
public final class EndpointSecretGenerator {

    private static final String PREFIX = "hrs_";
    private static final int RANDOM_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private EndpointSecretGenerator() {
    }

    public static String generate() {
        byte[] bytes = new byte[RANDOM_BYTES];
        RANDOM.nextBytes(bytes);
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
