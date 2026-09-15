package io.hookrelay.dispatcher.delivery;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.hookrelay.common.application.Application;
import io.hookrelay.common.application.ApplicationRepository;
import io.hookrelay.common.crypto.SecretEncryptionService;
import io.hookrelay.common.delivery.Delivery;
import io.hookrelay.common.delivery.DeliveryAttempt;
import io.hookrelay.common.delivery.DeliveryAttemptRepository;
import io.hookrelay.common.delivery.DeliveryRepository;
import io.hookrelay.common.delivery.DeliveryStatus;
import io.hookrelay.common.endpoint.Endpoint;
import io.hookrelay.common.endpoint.EndpointRepository;
import io.hookrelay.common.endpoint.EndpointSecret;
import io.hookrelay.common.endpoint.EndpointSecretRepository;
import io.hookrelay.common.event.Event;
import io.hookrelay.common.event.EventRepository;
import io.hookrelay.common.tenant.Tenant;
import io.hookrelay.common.tenant.TenantRepository;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class DeliveryExecutionServiceTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static WireMockServer wireMock;

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stopWireMock() {
        wireMock.stop();
    }

    @AfterEach
    void resetWireMock() {
        wireMock.resetAll();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // Only this test enables Flyway — the dispatcher itself never runs
        // migrations (hookrelay-api owns that), but the test needs *some*
        // schema in its Testcontainers database, and the migration files
        // live in hookrelay-common precisely so both modules can reach them.
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
        registry.add("hookrelay.security.secret-encryption-key", () -> "2P0OfgmRP7wHAAILerwZoJCC92TIw0RjZ4l0kXhIT/k=");
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
        // WireMock only ever runs on loopback, which EndpointUrlValidator
        // correctly blocks in production. See its Javadoc for why this
        // override exists and why it's never set outside tests.
        registry.add("hookrelay.security.ssrf-protection.enabled", () -> "false");
    }

    @Autowired
    private DeliveryExecutionService deliveryExecutionService;
    @Autowired
    private TenantRepository tenantRepository;
    @Autowired
    private ApplicationRepository applicationRepository;
    @Autowired
    private EndpointRepository endpointRepository;
    @Autowired
    private EndpointSecretRepository endpointSecretRepository;
    @Autowired
    private EventRepository eventRepository;
    @Autowired
    private DeliveryRepository deliveryRepository;
    @Autowired
    private DeliveryAttemptRepository deliveryAttemptRepository;
    @Autowired
    private SecretEncryptionService secretEncryptionService;

    private record Fixture(Delivery delivery, String rawSecret) {
    }

    private Fixture seedDelivery(String path) {
        Tenant tenant = tenantRepository.save(new Tenant("Dispatcher Test Tenant"));
        Application application = applicationRepository.save(new Application(tenant, "Dispatcher Test App"));
        Endpoint endpoint = endpointRepository.save(
                new Endpoint(application, wireMock.baseUrl() + path, "test"));
        String rawSecret = "whsec_test_secret_value";
        endpointSecretRepository.save(new EndpointSecret(endpoint, secretEncryptionService.encrypt(rawSecret)));
        Event event = eventRepository.save(
                new Event(application, "invoice.paid", "{\"amount\":100}", null, 20));
        Delivery delivery = deliveryRepository.save(new Delivery(event, endpoint));
        return new Fixture(delivery, rawSecret);
    }

    @Test
    void successfulDeliveryMarksSucceededAndSendsAValidSignature() throws Exception {
        wireMock.stubFor(WireMock.post("/hook").willReturn(aResponse().withStatus(200)));
        Fixture fixture = seedDelivery("/hook");

        deliveryExecutionService.execute(fixture.delivery().getId());

        Delivery reloaded = deliveryRepository.findById(fixture.delivery().getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(DeliveryStatus.SUCCEEDED);
        assertThat(reloaded.getAttemptCount()).isEqualTo(1);
        assertThat(reloaded.getCompletedAt()).isNotNull();

        var received = wireMock.findAll(postRequestedFor(urlEqualTo("/hook"))).get(0);
        assertThat(received.getHeader("Hookrelay-Id")).isEqualTo(fixture.delivery().getId().toString());
        String signatureHeader = received.getHeader("Hookrelay-Signature");
        assertThat(signatureHeader).startsWith("t=");
        assertThat(verifySignature(received.getBodyAsString(), signatureHeader, fixture.rawSecret())).isTrue();
    }

    @Test
    void serverErrorIsRetryableAndSchedulesAnotherAttempt() {
        wireMock.stubFor(WireMock.post("/hook-500").willReturn(aResponse().withStatus(500)));
        Fixture fixture = seedDelivery("/hook-500");

        deliveryExecutionService.execute(fixture.delivery().getId());

        Delivery reloaded = deliveryRepository.findById(fixture.delivery().getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(reloaded.getNextAttemptAt()).isAfter(Instant.now());
        assertThat(reloaded.getCompletedAt()).isNull();
    }

    @Test
    void clientErrorIsTerminalAndDoesNotRetry() {
        wireMock.stubFor(WireMock.post("/hook-400").willReturn(aResponse().withStatus(400).withBody("bad request")));
        Fixture fixture = seedDelivery("/hook-400");

        deliveryExecutionService.execute(fixture.delivery().getId());

        Delivery reloaded = deliveryRepository.findById(fixture.delivery().getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(DeliveryStatus.EXHAUSTED);
        assertThat(reloaded.getCompletedAt()).isNotNull();
    }

    @Test
    void rateLimitResponseIsRetryableDespiteBeingA4xx() {
        wireMock.stubFor(WireMock.post("/hook-429").willReturn(aResponse().withStatus(429)));
        Fixture fixture = seedDelivery("/hook-429");

        deliveryExecutionService.execute(fixture.delivery().getId());

        Delivery reloaded = deliveryRepository.findById(fixture.delivery().getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(reloaded.getNextAttemptAt()).isNotNull();
    }

    @Test
    void everyAttemptIsRecorded() {
        wireMock.stubFor(WireMock.post("/hook-record").willReturn(aResponse().withStatus(200)));
        Fixture fixture = seedDelivery("/hook-record");

        deliveryExecutionService.execute(fixture.delivery().getId());

        List<DeliveryAttempt> attempts = deliveryAttemptRepository.findAll().stream()
                .filter(a -> a.getDelivery().getId().equals(fixture.delivery().getId()))
                .toList();
        assertThat(attempts).hasSize(1);
        assertThat(attempts.get(0).getResponseStatus()).isEqualTo(200);
        assertThat(attempts.get(0).getAttemptNumber()).isEqualTo(1);
    }

    private boolean verifySignature(String rawBody, String signatureHeader, String secret) throws Exception {
        String[] parts = signatureHeader.split(",");
        long timestamp = Long.parseLong(parts[0].substring("t=".length()));
        String signedPayload = timestamp + "." + rawBody;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String expected = HexFormat.of().formatHex(mac.doFinal(signedPayload.getBytes(StandardCharsets.UTF_8)));
        for (int i = 1; i < parts.length; i++) {
            String candidate = parts[i].substring("v1=".length());
            if (MessageDigest.isEqual(candidate.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8))) {
                return true;
            }
        }
        return false;
    }
}
