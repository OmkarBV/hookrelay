package io.hookrelay.api.deliverylog;

import static org.assertj.core.api.Assertions.assertThat;

import io.hookrelay.common.admin.AdminRole;
import io.hookrelay.common.admin.AdminUser;
import io.hookrelay.common.admin.AdminUserRepository;
import io.hookrelay.common.application.Application;
import io.hookrelay.common.application.ApplicationRepository;
import io.hookrelay.common.delivery.Delivery;
import io.hookrelay.common.delivery.DeliveryAttempt;
import io.hookrelay.common.delivery.DeliveryAttemptRepository;
import io.hookrelay.common.delivery.DeliveryRepository;
import io.hookrelay.common.delivery.DeliveryStatus;
import io.hookrelay.common.endpoint.Endpoint;
import io.hookrelay.common.endpoint.EndpointRepository;
import io.hookrelay.common.event.Event;
import io.hookrelay.common.event.EventRepository;
import io.hookrelay.common.tenant.Tenant;
import io.hookrelay.common.tenant.TenantRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class DeliveryLogSearchTest {

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
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final String PASSWORD = "correct horse battery staple";

    private String login(String email) {
        Map<String, String> body = Map.of("email", email, "password", PASSWORD);
        ResponseEntity<Map> response = restTemplate.postForEntity("/api/v1/admin/auth/login", body, Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (String) response.getBody().get("token");
    }

    private HttpEntity<Void> authed(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return new HttpEntity<>(headers);
    }

    /** Backdates a delivery's created_at directly via JDBC for deterministic pagination ordering. */
    private void backdate(UUID deliveryId, Instant createdAt) {
        jdbcTemplate.update(
                "update delivery set created_at = ? where id = ?",
                java.sql.Timestamp.from(createdAt), deliveryId);
    }

    @Test
    void keysetPaginationReturnsEveryDeliveryExactlyOnceInDescendingOrder() {
        Tenant tenant = tenantRepository.save(new Tenant("Search Test Tenant"));
        AdminUser owner = adminUserRepository.save(
                new AdminUser(tenant, "owner@search-test.example", passwordEncoder.encode(PASSWORD), AdminRole.OWNER));
        Application application = applicationRepository.save(new Application(tenant, "Search Test App"));
        Endpoint endpoint = endpointRepository.save(new Endpoint(application, "https://example.com/hook", "test"));

        Instant base = Instant.now().minus(1, ChronoUnit.DAYS);
        List<UUID> idsOldestToNewest = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Event event = eventRepository.save(new Event(application, "invoice.paid", "{}", null, 2));
            Delivery delivery = deliveryRepository.save(new Delivery(event, endpoint));
            backdate(delivery.getId(), base.plusSeconds(i * 60L));
            idsOldestToNewest.add(delivery.getId());
        }

        String token = login("owner@search-test.example");
        Set<UUID> seen = new HashSet<>();
        String cursor = null;
        int pages = 0;
        do {
            String url = "/api/v1/admin/deliveries?endpointId=" + endpoint.getId() + "&limit=2"
                    + (cursor != null ? "&cursor=" + cursor : "");
            ResponseEntity<DeliveryListResponse> response =
                    restTemplate.exchange(url, HttpMethod.GET, authed(token), DeliveryListResponse.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            DeliveryListResponse body = response.getBody();
            for (DeliveryListItemResponse item : body.items()) {
                assertThat(seen.add(item.id())).as("no delivery should appear on two pages").isTrue();
            }
            cursor = body.nextCursor();
            pages++;
            assertThat(pages).isLessThan(10); // safety net against an infinite loop bug
        } while (cursor != null);

        assertThat(seen).containsExactlyInAnyOrderElementsOf(idsOldestToNewest);
    }

    @Test
    void filtersByStatusAndEventType() {
        Tenant tenant = tenantRepository.save(new Tenant("Filter Test Tenant"));
        adminUserRepository.save(
                new AdminUser(tenant, "owner@filter-test.example", passwordEncoder.encode(PASSWORD), AdminRole.OWNER));
        Application application = applicationRepository.save(new Application(tenant, "Filter Test App"));
        Endpoint endpoint = endpointRepository.save(new Endpoint(application, "https://example.com/hook", "test"));

        Event paidEvent = eventRepository.save(new Event(application, "invoice.paid", "{}", null, 2));
        Delivery succeeded = deliveryRepository.save(new Delivery(paidEvent, endpoint));
        succeeded.setStatus(DeliveryStatus.SUCCEEDED);
        deliveryRepository.save(succeeded);

        Event voidedEvent = eventRepository.save(new Event(application, "invoice.voided", "{}", null, 2));
        Delivery failed = deliveryRepository.save(new Delivery(voidedEvent, endpoint));
        failed.setStatus(DeliveryStatus.FAILED);
        deliveryRepository.save(failed);

        String token = login("owner@filter-test.example");

        ResponseEntity<DeliveryListResponse> byStatus = restTemplate.exchange(
                "/api/v1/admin/deliveries?status=SUCCEEDED", HttpMethod.GET, authed(token), DeliveryListResponse.class);
        assertThat(byStatus.getBody().items()).extracting(DeliveryListItemResponse::id).containsExactly(succeeded.getId());

        ResponseEntity<DeliveryListResponse> byEventType = restTemplate.exchange(
                "/api/v1/admin/deliveries?eventType=invoice.voided", HttpMethod.GET, authed(token), DeliveryListResponse.class);
        assertThat(byEventType.getBody().items()).extracting(DeliveryListItemResponse::id).containsExactly(failed.getId());
    }

    @Test
    void getByIdReturnsFullAttemptHistoryIncludingHeadersAndTruncatedBody() {
        Tenant tenant = tenantRepository.save(new Tenant("Detail Test Tenant"));
        adminUserRepository.save(
                new AdminUser(tenant, "owner@detail-test.example", passwordEncoder.encode(PASSWORD), AdminRole.OWNER));
        Application application = applicationRepository.save(new Application(tenant, "Detail Test App"));
        Endpoint endpoint = endpointRepository.save(new Endpoint(application, "https://example.com/hook", "test"));
        Event event = eventRepository.save(new Event(application, "invoice.paid", "{}", null, 2));
        Delivery delivery = deliveryRepository.save(new Delivery(event, endpoint));
        deliveryAttemptRepository.save(DeliveryAttempt.success(
                delivery, 1, "{\"Hookrelay-Id\":\"x\"}", 200, "response body", 42));
        deliveryAttemptRepository.save(DeliveryAttempt.failure(
                delivery, 2, "{\"Hookrelay-Id\":\"x\"}", 500, "err body", 10, "SERVER_ERROR"));

        String token = login("owner@detail-test.example");
        ResponseEntity<DeliveryDetailResponse> response = restTemplate.exchange(
                "/api/v1/admin/deliveries/" + delivery.getId(), HttpMethod.GET, authed(token), DeliveryDetailResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().delivery().id()).isEqualTo(delivery.getId());
        assertThat(response.getBody().attempts()).hasSize(2);
        assertThat(response.getBody().attempts().get(0).requestHeaders()).contains("Hookrelay-Id");
        assertThat(response.getBody().attempts().get(1).responseBodyTruncated()).isEqualTo("err body");
    }
}
