package io.hookrelay.api.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import io.hookrelay.api.security.apikey.ApiKeyGenerator;
import io.hookrelay.common.application.ApiKey;
import io.hookrelay.common.application.ApiKeyRepository;
import io.hookrelay.common.application.Application;
import io.hookrelay.common.application.ApplicationRepository;
import io.hookrelay.common.delivery.DeliveryPublisher;
import io.hookrelay.common.tenant.Tenant;
import io.hookrelay.common.tenant.TenantRepository;
import java.util.List;
import java.util.Map;
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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * A separate test class from EventIngestionTest specifically so it can tune
 * the rate limit to a tiny burst without affecting that class's own tests,
 * which need enough headroom that ordinary ingestion never gets throttled.
 */
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class IngestionRateLimitTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

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
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        // Small on purpose: 2 requests allowed, then throttled. The refill
        // rate is deliberately tiny (not 1/s) because each HTTP round trip
        // in this test takes on the order of a second once JPA/Hibernate
        // overhead is included — a 1 token/sec refill would silently top
        // the bucket back up between requests and make the test flaky.
        registry.add("hookrelay.ingestion.rate-limit.requests-per-second", () -> "0.05");
        registry.add("hookrelay.ingestion.rate-limit.burst-capacity", () -> "2");
    }

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private TenantRepository tenantRepository;
    @Autowired
    private ApplicationRepository applicationRepository;
    @Autowired
    private ApiKeyRepository apiKeyRepository;

    @MockBean
    private DeliveryPublisher deliveryPublisher;

    private HttpEntity<Map<String, Object>> requestWithKey(String rawApiKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(rawApiKey);
        Map<String, Object> body = Map.of("eventType", "invoice.paid", "payload", Map.of("x", 1));
        return new HttpEntity<>(body, headers);
    }

    @Test
    void exceedingTheBurstReturns429WithRetryAfterAndDoesNotStarveOtherApplications() {
        Tenant tenant = tenantRepository.save(new Tenant("Rate Limit Test Tenant"));
        Application noisyApp = applicationRepository.save(new Application(tenant, "Noisy App"));
        String noisyKey = ApiKeyGenerator.generate();
        apiKeyRepository.save(new ApiKey(noisyApp, ApiKeyGenerator.hash(noisyKey)));
        Application quietApp = applicationRepository.save(new Application(tenant, "Quiet App"));
        String quietKey = ApiKeyGenerator.generate();
        apiKeyRepository.save(new ApiKey(quietApp, ApiKeyGenerator.hash(quietKey)));

        List<HttpStatus> statuses = new java.util.ArrayList<>();
        for (int i = 0; i < 3; i++) {
            ResponseEntity<String> response = restTemplate.postForEntity(
                    "/api/v1/events", requestWithKey(noisyKey), String.class);
            statuses.add((HttpStatus) response.getStatusCode());
        }

        assertThat(statuses).containsExactly(HttpStatus.ACCEPTED, HttpStatus.ACCEPTED, HttpStatus.TOO_MANY_REQUESTS);

        ResponseEntity<String> throttled = restTemplate.postForEntity(
                "/api/v1/events", requestWithKey(noisyKey), String.class);
        assertThat(throttled.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(throttled.getHeaders().getFirst("Retry-After")).isNotNull();

        // The point of a per-application bucket: a second application isn't
        // affected by the first one being throttled.
        ResponseEntity<String> quiet = restTemplate.postForEntity(
                "/api/v1/events", requestWithKey(quietKey), String.class);
        assertThat(quiet.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }
}
