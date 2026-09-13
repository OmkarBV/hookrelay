package io.hookrelay.api.security.apikey;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Generates ingestion API keys and hashes them for storage.
 *
 * <p>Hashed with SHA-256, not BCrypt. BCrypt's deliberate slowness exists to
 * defend low-entropy, human-chosen passwords against offline brute force.
 * These keys are 256 bits of {@link SecureRandom} output — brute-forcing one
 * from its hash is already computationally infeasible, so BCrypt would add
 * roughly 100ms of pure overhead to every ingested request for no security
 * benefit, directly conflicting with the p99 &lt; 50ms ingestion target
 * (Phase 3). A fast, unsalted hash is safe here specifically because the
 * input is high-entropy and never human-chosen or reused across systems.
 */
public final class ApiKeyGenerator {

    private static final String PREFIX = "hr_live_";
    private static final int RANDOM_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private ApiKeyGenerator() {
    }

    public static String generate() {
        byte[] bytes = new byte[RANDOM_BYTES];
        RANDOM.nextBytes(bytes);
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String hash(String rawKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawKey.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
