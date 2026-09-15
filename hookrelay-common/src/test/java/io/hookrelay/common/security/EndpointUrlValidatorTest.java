package io.hookrelay.common.security;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;

class EndpointUrlValidatorTest {

    private final EndpointUrlValidator validator = new EndpointUrlValidator(true);

    @Test
    void blocksLoopback() {
        assertThatThrownBy(() -> validator.validate("http://127.0.0.1/hook"))
                .isInstanceOf(SsrfViolationException.class);
        assertThatThrownBy(() -> validator.validate("http://localhost/hook"))
                .isInstanceOf(SsrfViolationException.class);
    }

    @Test
    void blocksCloudMetadataEndpoint() {
        assertThatThrownBy(() -> validator.validate("http://169.254.169.254/latest/meta-data/"))
                .isInstanceOf(SsrfViolationException.class);
    }

    @Test
    void blocksPrivateRfc1918Ranges() {
        assertThatThrownBy(() -> validator.validate("http://10.0.0.5/hook"))
                .isInstanceOf(SsrfViolationException.class);
        assertThatThrownBy(() -> validator.validate("http://192.168.1.1/hook"))
                .isInstanceOf(SsrfViolationException.class);
        assertThatThrownBy(() -> validator.validate("http://172.16.0.1/hook"))
                .isInstanceOf(SsrfViolationException.class);
    }

    @Test
    void rejectsNonHttpSchemes() {
        assertThatThrownBy(() -> validator.validate("ftp://example.com/hook"))
                .isInstanceOf(SsrfViolationException.class);
        assertThatThrownBy(() -> validator.validate("file:///etc/passwd"))
                .isInstanceOf(SsrfViolationException.class);
    }

    @Test
    void allowsAPublicAddress() {
        // 8.8.8.8 (Google public DNS) is long-stable public infrastructure;
        // validated by literal IP so this test doesn't depend on a specific
        // hostname's DNS record.
        assertThatCode(() -> validator.validate("http://8.8.8.8/hook"))
                .doesNotThrowAnyException();
    }

    @Test
    void disablingEnforcementAllowsLoopback() {
        EndpointUrlValidator unenforced = new EndpointUrlValidator(false);
        assertThatCode(() -> unenforced.validate("http://127.0.0.1/hook")).doesNotThrowAnyException();
    }

    @Test
    void disablingEnforcementStillRejectsMalformedOrNonHttpUrls() {
        EndpointUrlValidator unenforced = new EndpointUrlValidator(false);
        assertThatThrownBy(() -> unenforced.validate("ftp://127.0.0.1/hook"))
                .isInstanceOf(SsrfViolationException.class);
    }
}
