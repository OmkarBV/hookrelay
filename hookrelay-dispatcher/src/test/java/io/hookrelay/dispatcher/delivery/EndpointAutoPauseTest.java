package io.hookrelay.dispatcher.delivery;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.hookrelay.common.application.Application;
import io.hookrelay.common.application.ApplicationRepository;
import io.hookrelay.common.crypto.SecretEncryptionService;
import io.hookrelay.common.delivery.Delivery;
import io.hookrelay.common.delivery.DeliveryRepository;
import io.hookrelay.common.endpoint.Endpoint;
import io.hookrelay.common.endpoint.EndpointRepository;
import io.hookrelay.common.endpoint.EndpointSecret;
import io.hookrelay.common.endpoint.EndpointSecretRepository;
import io.hookrelay.common.endpoint.EndpointStatus;
import io.hookrelay.common.event.Event;
import io.hookrelay.common.event.EventRepository;
import io.hookrelay.common.tenant.Tenant;
import io.hookrelay.common.tenant.TenantRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * A separate test class (rather than more methods on
 * DeliveryExecutionServiceTest) specifically so it can tune the circuit
 * breaker to a small, fast-to-trigger threshold without affecting that
 * class's own tests, which rely on the default threshold being high enough
 * that a single failure never opens a circuit.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class EndpointAutoPauseTest {

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

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
        registry.add("hookrelay.security.secret-encryption-key", () -> "2P0OfgmRP7wHAAILerwZoJCC92TIw0RjZ4l0kXhIT/k=");
        registry.add("hookrelay.security.ssrf-protection.enabled", () -> "false");
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
        registry.add("spring.kafka.admin.properties.request.timeout.ms", () -> "500");
        registry.add("spring.kafka.admin.properties.default.api.timeout.ms", () -> "1000");
        registry.add("spring.kafka.admin.properties.retries", () -> "0");
        registry.add("spring.kafka.admin.fail-fast", () -> "false");
        // Small on purpose: 2 consecutive failures opens the circuit, and a
        // single open (auto-pause-after-opens=1) immediately escalates to a
        // pause, so this test doesn't need to wait through a real
        // half-open recovery cycle to observe the escalation.
        registry.add("hookrelay.dispatcher.circuit-breaker.failure-threshold", () -> "2");
        registry.add("hookrelay.dispatcher.circuit-breaker.wait-duration-seconds", () -> "60");
        registry.add("hookrelay.dispatcher.circuit-breaker.auto-pause-after-opens", () -> "1");
        registry.add("hookrelay.dispatcher.scheduling.enabled", () -> "false");
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
    private SecretEncryptionService secretEncryptionService;

    @Test
    void repeatedFailuresOpenTheCircuitAndAutoPauseTheEndpoint() {
        wireMock.stubFor(WireMock.post("/always-down").willReturn(aResponse().withStatus(500)));

        Tenant tenant = tenantRepository.save(new Tenant("Auto Pause Test Tenant"));
        Application application = applicationRepository.save(new Application(tenant, "Auto Pause Test App"));
        Endpoint endpoint = endpointRepository.save(
                new Endpoint(application, wireMock.baseUrl() + "/always-down", "test"));
        endpointSecretRepository.save(new EndpointSecret(endpoint, secretEncryptionService.encrypt("secret")));

        // Two consecutive failing deliveries: the first opens no circuit yet
        // (minimumNumberOfCalls not reached until the 2nd), the second
        // pushes it over the threshold and should trigger auto-pause.
        for (int i = 0; i < 2; i++) {
            Event event = eventRepository.save(new Event(application, "invoice.paid", "{}", null, 2));
            Delivery delivery = deliveryRepository.save(new Delivery(event, endpoint));
            deliveryExecutionService.execute(delivery.getId());
        }

        Endpoint reloaded = endpointRepository.findById(endpoint.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(EndpointStatus.PAUSED);
        assertThat(reloaded.getPausedReason()).isNotNull();
        assertThat(reloaded.getPausedAt()).isNotNull();
    }

    @Test
    void aSingleFailureDoesNotOpenTheCircuitOrPause() {
        wireMock.stubFor(WireMock.post("/flaky-once").willReturn(aResponse().withStatus(500)));

        Tenant tenant = tenantRepository.save(new Tenant("Single Failure Tenant"));
        Application application = applicationRepository.save(new Application(tenant, "Single Failure App"));
        Endpoint endpoint = endpointRepository.save(
                new Endpoint(application, wireMock.baseUrl() + "/flaky-once", "test"));
        endpointSecretRepository.save(new EndpointSecret(endpoint, secretEncryptionService.encrypt("secret")));
        Event event = eventRepository.save(new Event(application, "invoice.paid", "{}", null, 2));
        Delivery delivery = deliveryRepository.save(new Delivery(event, endpoint));

        deliveryExecutionService.execute(delivery.getId());

        Endpoint reloaded = endpointRepository.findById(endpoint.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(EndpointStatus.ACTIVE);
        assertThat(reloaded.getPausedReason()).isNull();
    }
}
