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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class DeliveryExecutionServiceTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

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
        registry.add("spring.kafka.admin.properties.request.timeout.ms", () -> "500");
        registry.add("spring.kafka.admin.properties.default.api.timeout.ms", () -> "1000");
        registry.add("spring.kafka.admin.properties.retries", () -> "0");
        registry.add("spring.kafka.admin.fail-fast", () -> "false");
        // This test drives DeliveryExecutionService.execute() directly; the
        // @Scheduled retry sweeper and partition-maintenance job have
        // nothing to do here and would otherwise keep firing against this
        // test's Postgres container after it's torn down.
        registry.add("hookrelay.dispatcher.scheduling.enabled", () -> "false");
        // WireMock only ever runs on loopback, which EndpointUrlValidator
        // correctly blocks in production. See its Javadoc for why this
        // override exists and why it's never set outside tests.
        registry.add("hookrelay.security.ssrf-protection.enabled", () -> "false");
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
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
        return seedDelivery(path, 10);
    }

    private Fixture seedDelivery(String path, int rateLimitPerSec) {
        Tenant tenant = tenantRepository.save(new Tenant("Dispatcher Test Tenant"));
        Application application = applicationRepository.save(new Application(tenant, "Dispatcher Test App"));
        Endpoint endpoint = new Endpoint(application, wireMock.baseUrl() + path, "test");
        endpoint.setRateLimitPerSec(rateLimitPerSec);
        endpoint = endpointRepository.save(endpoint);
        String rawSecret = "whsec_test_secret_value";
        endpointSecretRepository.save(new EndpointSecret(endpoint, secretEncryptionService.encrypt(rawSecret)));
        Event event = eventRepository.save(
                new Event(application, "invoice.paid", "{\"amount\":100}", null, 20));
        Delivery delivery = deliveryRepository.save(new Delivery(event, endpoint));
        return new Fixture(delivery, rawSecret);
    }

    private Delivery anotherDeliveryForSameEndpoint(Fixture fixture) {
        Event event = eventRepository.save(new Event(
                fixture.delivery().getEvent().getApplication(), "invoice.paid", "{\"amount\":200}", null, 20));
        return deliveryRepository.save(new Delivery(event, fixture.delivery().getEndpoint()));
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
    void retryAfterHeaderOnA429OverridesTheDefaultBackoffSchedule() {
        wireMock.stubFor(WireMock.post("/hook-429-retry-after")
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "300")));
        Fixture fixture = seedDelivery("/hook-429-retry-after");

        deliveryExecutionService.execute(fixture.delivery().getId());

        Delivery reloaded = deliveryRepository.findById(fixture.delivery().getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(DeliveryStatus.FAILED);
        // Default first-failure backoff (jittered 5s) would land well under
        // a minute away; 300s only shows up here if the header was honored.
        assertThat(reloaded.getNextAttemptAt()).isAfter(Instant.now().plusSeconds(250));
    }

    @Test
    void outboundRateLimitThrottlesFurtherAttemptsToTheSameEndpointWithoutCallingIt() {
        // Capacity == refill rate == 1/s: the first attempt consumes the
        // sole token, so a second attempt made immediately after has none
        // left and must not reach the receiver at all.
        Fixture fixture = seedDelivery("/hook-rate-limited", 1);
        wireMock.stubFor(WireMock.post("/hook-rate-limited").willReturn(aResponse().withStatus(200)));
        Delivery second = anotherDeliveryForSameEndpoint(fixture);

        deliveryExecutionService.execute(fixture.delivery().getId());
        deliveryExecutionService.execute(second.getId());

        assertThat(wireMock.findAll(postRequestedFor(urlEqualTo("/hook-rate-limited")))).hasSize(1);
        Delivery reloadedSecond = deliveryRepository.findById(second.getId()).orElseThrow();
        assertThat(reloadedSecond.getStatus()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(reloadedSecond.getLastError()).contains("rate limit");
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

    @Autowired
    private io.micrometer.core.instrument.MeterRegistry meterRegistry;

    @Test
    void successfulDeliveryIncrementsTheStatusCounterAndRecordsLatencyAndLeavesInFlightAtZero() {
        wireMock.stubFor(WireMock.post("/hook-metrics").willReturn(aResponse().withStatus(200)));
        Fixture fixture = seedDelivery("/hook-metrics");

        double before = meterRegistry.get("hookrelay.deliveries.total")
                .tag("status", "SUCCEEDED").tag("error_type", "none").counter().count();

        deliveryExecutionService.execute(fixture.delivery().getId());

        double after = meterRegistry.get("hookrelay.deliveries.total")
                .tag("status", "SUCCEEDED").tag("error_type", "none").counter().count();
        assertThat(after).isEqualTo(before + 1);
        assertThat(meterRegistry.get("hookrelay.delivery.attempt.latency").tag("status", "SUCCEEDED")
                .timer().count()).isGreaterThan(0);
        assertThat(meterRegistry.get("hookrelay.delivery.inflight").gauge().value()).isEqualTo(0.0);
    }

    @Test
    void correlationIdIsSetDuringExecutionAndClearedAfterward() {
        wireMock.stubFor(WireMock.post("/hook-correlation").willReturn(aResponse().withStatus(200)));
        Fixture fixture = seedDelivery("/hook-correlation");
        assertThat(fixture.delivery().getEvent().getCorrelationId()).isNotBlank();

        deliveryExecutionService.execute(fixture.delivery().getId());

        assertThat(org.slf4j.MDC.get("correlationId")).isNull();
    }

    @Test
    void failedDeliveryIsCountedUnderItsErrorType() {
        wireMock.stubFor(WireMock.post("/hook-metrics-fail").willReturn(aResponse().withStatus(500)));
        Fixture fixture = seedDelivery("/hook-metrics-fail");

        double before = meterRegistry.get("hookrelay.deliveries.total")
                .tag("status", "FAILED").tag("error_type", "SERVER_ERROR").counter().count();

        deliveryExecutionService.execute(fixture.delivery().getId());

        double after = meterRegistry.get("hookrelay.deliveries.total")
                .tag("status", "FAILED").tag("error_type", "SERVER_ERROR").counter().count();
        assertThat(after).isEqualTo(before + 1);
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
