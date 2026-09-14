package io.hookrelay.api.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.hookrelay.api.security.apikey.ApiKeyGenerator;
import io.hookrelay.common.application.ApiKey;
import io.hookrelay.common.application.ApiKeyRepository;
import io.hookrelay.common.application.Application;
import io.hookrelay.common.application.ApplicationRepository;
import io.hookrelay.common.delivery.DeliveryRepository;
import io.hookrelay.common.endpoint.Endpoint;
import io.hookrelay.common.endpoint.EndpointRepository;
import io.hookrelay.common.endpoint.EndpointStatus;
import io.hookrelay.common.event.EventRepository;
import io.hookrelay.common.tenant.Tenant;
import io.hookrelay.common.tenant.TenantRepository;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Exercises ingestion end to end against a real Postgres, but with
 * DeliveryPublisher mocked out — this suite is about the DB-side contract
 * (fan-out, idempotency, validation), not Kafka wire behavior, which Phase 9
 * covers with a full Testcontainers Kafka broker.
 */
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class EventIngestionTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("hookrelay.security.jwt.secret", () -> "test-secret-test-secret-test-secret-test-secret");
        registry.add("spring.kafka.admin.properties.request.timeout.ms", () -> "500");
        registry.add("spring.kafka.admin.properties.default.api.timeout.ms", () -> "1000");
        registry.add("spring.kafka.admin.properties.retries", () -> "0");
        registry.add("spring.kafka.admin.fail-fast", () -> "false");
    }

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private TenantRepository tenantRepository;
    @Autowired
    private ApplicationRepository applicationRepository;
    @Autowired
    private ApiKeyRepository apiKeyRepository;
    @Autowired
    private EndpointRepository endpointRepository;
    @Autowired
    private EventRepository eventRepository;
    @Autowired
    private DeliveryRepository deliveryRepository;

    @MockBean
    private DeliveryPublisher deliveryPublisher;

    private String rawApiKey;
    private Application application;

    @BeforeEach
    void seedApplicationWithApiKey() {
        Tenant tenant = tenantRepository.save(new Tenant("Ingestion Test Tenant"));
        application = applicationRepository.save(new Application(tenant, "Ingestion Test App"));
        rawApiKey = ApiKeyGenerator.generate();
        apiKeyRepository.save(new ApiKey(application, ApiKeyGenerator.hash(rawApiKey)));
    }

    private HttpEntity<Map<String, Object>> requestWithKey(Map<String, Object> body, String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(rawApiKey);
        if (idempotencyKey != null) {
            headers.add("Idempotency-Key", idempotencyKey);
        }
        return new HttpEntity<>(body, headers);
    }

    @Test
    void ingestsAndFansOutToSubscribedActiveEndpointsOnly() {
        Endpoint subscribedActive = endpointRepository.save(subscribed(application, "invoice.paid", EndpointStatus.ACTIVE));
        Endpoint subscribedPaused = endpointRepository.save(subscribed(application, "invoice.paid", EndpointStatus.PAUSED));
        Endpoint differentEventType = endpointRepository.save(subscribed(application, "invoice.voided", EndpointStatus.ACTIVE));

        Map<String, Object> body = Map.of("eventType", "invoice.paid", "payload", Map.of("amount", 100));
        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/api/v1/events", requestWithKey(body, null), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        UUID eventId = UUID.fromString((String) response.getBody().get("eventId"));
        assertThat(eventRepository.findById(eventId)).isPresent();

        verify(deliveryPublisher, times(1)).publish(any(), org.mockito.ArgumentMatchers.eq(subscribedActive.getId()));
        verify(deliveryPublisher, never()).publish(any(), org.mockito.ArgumentMatchers.eq(subscribedPaused.getId()));
        verify(deliveryPublisher, never()).publish(any(), org.mockito.ArgumentMatchers.eq(differentEventType.getId()));
    }

    @Test
    void duplicateIdempotencyKeyReturnsOriginalEventIdWithoutRepublishing() {
        endpointRepository.save(subscribed(application, "invoice.paid", EndpointStatus.ACTIVE));
        Map<String, Object> body = Map.of("eventType", "invoice.paid", "payload", Map.of("amount", 100));

        ResponseEntity<Map> first = restTemplate.postForEntity(
                "/api/v1/events", requestWithKey(body, "idem-key-1"), Map.class);
        ResponseEntity<Map> second = restTemplate.postForEntity(
                "/api/v1/events", requestWithKey(body, "idem-key-1"), Map.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(second.getBody().get("eventId")).isEqualTo(first.getBody().get("eventId"));

        // Exactly one publish across both requests — the retry must not fan out again.
        verify(deliveryPublisher, times(1)).publish(any(), any());
    }

    @Test
    void rejectsMalformedEventType() {
        Map<String, Object> body = Map.of("eventType", "not-namespaced", "payload", Map.of("x", 1));
        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/api/v1/events", requestWithKey(body, null), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectsOversizedPayload() {
        String bigValue = "x".repeat(300 * 1024);
        Map<String, Object> body = Map.of("eventType", "invoice.paid", "payload", Map.of("blob", bigValue));
        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/api/v1/events", requestWithKey(body, null), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
    }

    @Test
    void rejectsRequestsWithoutAValidApiKey() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("hr_live_not_a_real_key");
        Map<String, Object> body = Map.of("eventType", "invoice.paid", "payload", Map.of("x", 1));

        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/api/v1/events", new HttpEntity<>(body, headers), Map.class);

        assertThat(response.getStatusCode()).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
    }

    private static Endpoint subscribed(Application application, String eventType, EndpointStatus status) {
        Endpoint endpoint = new Endpoint(application, "https://example.com/hook", "test");
        endpoint.subscribeTo(eventType);
        endpoint.setStatus(status);
        return endpoint;
    }
}
