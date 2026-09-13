package io.hookrelay.api.security.apikey;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ApiKeyGeneratorTest {

    @Test
    void generatesDistinctKeysWithExpectedPrefix() {
        String first = ApiKeyGenerator.generate();
        String second = ApiKeyGenerator.generate();

        assertThat(first).startsWith("hr_live_");
        assertThat(second).startsWith("hr_live_");
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void hashIsDeterministicAndDoesNotLeakTheRawKey() {
        String rawKey = ApiKeyGenerator.generate();

        String hash1 = ApiKeyGenerator.hash(rawKey);
        String hash2 = ApiKeyGenerator.hash(rawKey);

        assertThat(hash1).isEqualTo(hash2);
        assertThat(hash1).doesNotContain(rawKey);
        assertThat(hash1).hasSize(64); // hex-encoded SHA-256
    }

    @Test
    void differentKeysHashDifferently() {
        String hashA = ApiKeyGenerator.hash(ApiKeyGenerator.generate());
        String hashB = ApiKeyGenerator.hash(ApiKeyGenerator.generate());

        assertThat(hashA).isNotEqualTo(hashB);
    }
}
