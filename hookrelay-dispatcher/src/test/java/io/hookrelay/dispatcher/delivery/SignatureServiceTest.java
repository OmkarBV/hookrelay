package io.hookrelay.dispatcher.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class SignatureServiceTest {

    private final SignatureService signatureService = new SignatureService();

    @Test
    void signsTimestampDotBodyNotBodyAlone() throws Exception {
        String header = signatureService.buildSignatureHeader("{\"a\":1}", 1_700_000_000L, List.of("secret"));

        assertThat(header).startsWith("t=1700000000,v1=");
        String signature = header.substring(header.indexOf("v1=") + 3);
        assertThat(signature).isEqualTo(hmacHex("1700000000.{\"a\":1}", "secret"));
        // signing the body alone must NOT match — proves the timestamp is bound in
        assertThat(signature).isNotEqualTo(hmacHex("{\"a\":1}", "secret"));
    }

    @Test
    void emitsOneV1ValuePerSecretForRotation() {
        String header = signatureService.buildSignatureHeader("body", 1_700_000_000L, List.of("old-secret", "new-secret"));

        assertThat(header.split(",v1=").length - 1).isEqualTo(2);
    }

    @Test
    void differentSecretsProduceDifferentSignatures() {
        String headerA = signatureService.buildSignatureHeader("body", 1L, List.of("secret-a"));
        String headerB = signatureService.buildSignatureHeader("body", 1L, List.of("secret-b"));

        assertThat(headerA).isNotEqualTo(headerB);
    }

    private String hmacHex(String data, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }
}
