package io.hookrelay.api.deliverylog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;

import io.hookrelay.common.admin.AdminRole;
import io.hookrelay.common.admin.AdminUser;
import io.hookrelay.common.admin.AdminUserRepository;
import io.hookrelay.common.application.Application;
import io.hookrelay.common.application.ApplicationRepository;
import io.hookrelay.common.delivery.Delivery;
import io.hookrelay.common.delivery.DeliveryAttempt;
import io.hookrelay.common.delivery.DeliveryAttemptRepository;
import io.hookrelay.common.delivery.DeliveryPublisher;
import io.hookrelay.common.delivery.DeliveryRepository;
import io.hookrelay.common.delivery.DeliveryStatus;
import io.hookrelay.common.endpoint.Endpoint;
import io.hookrelay.common.endpoint.EndpointRepository;
import io.hookrelay.common.event.Event;
import io.hookrelay.common.event.EventRepository;
import io.hookrelay.common.tenant.Tenant;
import io.hookrelay.common.tenant.TenantRepository;
import java.time.Instant;
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
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class DeliveryReplayTest {

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
        // Small caps so the guard-rail tests don't need to create hundreds
        // of fixture rows to exercise them.
        registry.add("hookrelay.replay.bulk.max-batch-size", () -> "3");
        registry.add("hookrelay.replay.bulk.rate-limit-per-minute", () -> "2");
    }

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private TenantRepository tenantRepository;
    @Autowired
    private AdminUserRepository adminUserRepository;
    @Autowired
    private ApplicationRepository applicationRepository;
    @Autowired
    private EndpointRepository endpointRepository;
    @Autowired
    private EventRepository eventRepository;
    @Autowired
    private DeliveryRepository deliveryRepository;
    @Autowired
    private DeliveryAttemptRepository deliveryAttemptRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockBean
    private DeliveryPublisher deliveryPublisher;

    private static final String PASSWORD = "correct horse battery staple";

    private String login(String email) {
        Map<String, String> body = Map.of("email", email, "password", PASSWORD);
        ResponseEntity<Map> response = restTemplate.postForEntity("/api/v1/admin/auth/login", body, Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (String) response.getBody().get("token");
    }

    private HttpEntity<Object> authed(String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private record Fixtures(Tenant tenant, Application application, Endpoint endpoint, String ownerToken, String viewerToken) {
    }

    private Fixtures seedTenant(String suffix) {
        Tenant tenant = tenantRepository.save(new Tenant("Replay Test Tenant " + suffix));
        adminUserRepository.save(new AdminUser(
                tenant, "owner-" + suffix + "@replay-test.example", passwordEncoder.encode(PASSWORD), AdminRole.OWNER));
        adminUserRepository.save(new AdminUser(
                tenant, "viewer-" + suffix + "@replay-test.example", passwordEncoder.encode(PASSWORD), AdminRole.VIEWER));
        Application application = applicationRepository.save(new Application(tenant, "Replay Test App " + suffix));
        Endpoint endpoint = endpointRepository.save(new Endpoint(application, "https://example.com/hook", "test"));
        String ownerToken = login("owner-" + suffix + "@replay-test.example");
        String viewerToken = login("viewer-" + suffix + "@replay-test.example");
        return new Fixtures(tenant, application, endpoint, ownerToken, viewerToken);
    }

    private Delivery seedDelivery(Application application, Endpoint endpoint, DeliveryStatus status) {
        Event event = eventRepository.save(new Event(application, "invoice.paid", "{}", null, 2));
        Delivery delivery = deliveryRepository.save(new Delivery(event, endpoint));
        delivery.setStatus(status);
        delivery.setAttemptCount(3);
        delivery.setCompletedAt(Instant.now());
        deliveryRepository.save(delivery);
        deliveryAttemptRepository.save(DeliveryAttempt.failure(delivery, 1, null, 500, null, 5, "SERVER_ERROR"));
        return delivery;
    }

    @Test
    void singleReplayCreatesNewDeliveryWithoutTouchingOriginalHistory() {
        Fixtures fx = seedTenant("single");
        Delivery original = seedDelivery(fx.application(), fx.endpoint(), DeliveryStatus.EXHAUSTED);

        ResponseEntity<DeliveryLogController.ReplayResponse> response = restTemplate.exchange(
                "/api/v1/admin/deliveries/" + original.getId() + "/replay",
                HttpMethod.POST, authed(fx.ownerToken(), null), DeliveryLogController.ReplayResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        var replayId = response.getBody().newDeliveryId();
        assertThat(replayId).isNotEqualTo(original.getId());

        Delivery replay = deliveryRepository.findById(replayId).orElseThrow();
        assertThat(replay.isReplay()).isTrue();
        assertThat(replay.getReplayedFromDeliveryId()).isEqualTo(original.getId());
        assertThat(replay.getStatus()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(replay.getAttemptCount()).isZero();

        // Original untouched.
        Delivery reloadedOriginal = deliveryRepository.findById(original.getId()).orElseThrow();
        assertThat(reloadedOriginal.getStatus()).isEqualTo(DeliveryStatus.EXHAUSTED);
        assertThat(reloadedOriginal.getAttemptCount()).isEqualTo(3);
        assertThat(deliveryAttemptRepository.findByDeliveryIdOrderByAttemptedAtAsc(original.getId())).hasSize(1);
        assertThat(deliveryAttemptRepository.findByDeliveryIdOrderByAttemptedAtAsc(replayId)).isEmpty();

        verify(deliveryPublisher, atLeast(1)).publish(replayId, fx.endpoint().getId());
    }

    @Test
    void viewerCannotReplay() {
        Fixtures fx = seedTenant("permission");
        Delivery original = seedDelivery(fx.application(), fx.endpoint(), DeliveryStatus.EXHAUSTED);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/deliveries/" + original.getId() + "/replay",
                HttpMethod.POST, authed(fx.viewerToken(), null), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void bulkReplayRequiresConfirmation() {
        Fixtures fx = seedTenant("noconfirm");
        seedDelivery(fx.application(), fx.endpoint(), DeliveryStatus.EXHAUSTED);

        var request = new BulkReplayRequest(fx.endpoint().getId(), DeliveryStatus.EXHAUSTED, null, null, null, false);
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/deliveries/replay", HttpMethod.POST, authed(fx.ownerToken(), request), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void bulkReplayRejectsWhenTooManyDeliveriesMatch() {
        Fixtures fx = seedTenant("toomany");
        // Cap is configured to 3 for this test class; seed 4 matches.
        for (int i = 0; i < 4; i++) {
            seedDelivery(fx.application(), fx.endpoint(), DeliveryStatus.EXHAUSTED);
        }

        var request = new BulkReplayRequest(fx.endpoint().getId(), DeliveryStatus.EXHAUSTED, null, null, null, true);
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/deliveries/replay", HttpMethod.POST, authed(fx.ownerToken(), request), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("3");
    }

    @Test
    void bulkReplayWithinCapReplaysEveryMatchWithoutTouchingOriginals() {
        Fixtures fx = seedTenant("bulkok");
        List<Delivery> originals = List.of(
                seedDelivery(fx.application(), fx.endpoint(), DeliveryStatus.EXHAUSTED),
                seedDelivery(fx.application(), fx.endpoint(), DeliveryStatus.EXHAUSTED));

        var request = new BulkReplayRequest(fx.endpoint().getId(), DeliveryStatus.EXHAUSTED, null, null, null, true);
        ResponseEntity<DeliveryLogController.BulkReplayResponse> response = restTemplate.exchange(
                "/api/v1/admin/deliveries/replay", HttpMethod.POST, authed(fx.ownerToken(), request),
                DeliveryLogController.BulkReplayResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody().replayedCount()).isEqualTo(2);

        for (Delivery original : originals) {
            Delivery reloaded = deliveryRepository.findById(original.getId()).orElseThrow();
            assertThat(reloaded.getStatus()).isEqualTo(DeliveryStatus.EXHAUSTED);
            assertThat(deliveryAttemptRepository.findByDeliveryIdOrderByAttemptedAtAsc(original.getId())).hasSize(1);
        }
        for (var newId : response.getBody().newDeliveryIds()) {
            assertThat(deliveryRepository.findById(newId).orElseThrow().isReplay()).isTrue();
        }
    }

    @Test
    void bulkReplayIsRateLimitedPerTenant() {
        Fixtures fx = seedTenant("ratelimit");
        var request = new BulkReplayRequest(fx.endpoint().getId(), DeliveryStatus.EXHAUSTED, null, null, null, true);

        // Rate limit configured to 2/minute for this test class. Fire 3
        // requests (each matching zero deliveries is fine — the limiter
        // gates before the query runs) and expect the 3rd to be throttled.
        HttpStatus last = null;
        for (int i = 0; i < 3; i++) {
            ResponseEntity<String> response = restTemplate.exchange(
                    "/api/v1/admin/deliveries/replay", HttpMethod.POST, authed(fx.ownerToken(), request), String.class);
            last = (HttpStatus) response.getStatusCode();
        }
        assertThat(last).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }
}
