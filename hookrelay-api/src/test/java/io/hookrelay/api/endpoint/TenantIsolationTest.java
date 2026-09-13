package io.hookrelay.api.endpoint;

import static org.assertj.core.api.Assertions.assertThat;

import io.hookrelay.common.admin.AdminRole;
import io.hookrelay.common.admin.AdminUser;
import io.hookrelay.common.admin.AdminUserRepository;
import io.hookrelay.common.application.Application;
import io.hookrelay.common.application.ApplicationRepository;
import io.hookrelay.common.endpoint.Endpoint;
import io.hookrelay.common.endpoint.EndpointRepository;
import io.hookrelay.common.tenant.Tenant;
import io.hookrelay.common.tenant.TenantRepository;
import java.util.Map;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The required Phase 2 test: a lookup of another tenant's endpoint must
 * behave exactly like a lookup of an id that doesn't exist (404), never
 * reveal that the row exists via a 403. Exercised through real HTTP calls
 * against a real Postgres, not mocks, since the guarantee being tested is
 * that the Hibernate tenant filter (and its repository-base-class fix for
 * findById) actually reaches the database correctly end to end.
 */
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class TenantIsolationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("hookrelay.security.jwt.secret", () -> "test-secret-test-secret-test-secret-test-secret");
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
    private PasswordEncoder passwordEncoder;

    private static final String PASSWORD = "correct horse battery staple";

    private String login(String email) {
        Map<String, String> body = Map.of("email", email, "password", PASSWORD);
        ResponseEntity<Map> response =
                restTemplate.postForEntity("/api/v1/admin/auth/login", body, Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (String) response.getBody().get("token");
    }

    private HttpEntity<Void> authed(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return new HttpEntity<>(headers);
    }

    @Test
    void tenantCannotFetchAnotherTenantsEndpoint_getsFourOhFourNotForbidden() {
        // Provisioned directly via repositories, the way an operator would seed
        // additional tenants in a real deployment (there is deliberately no
        // public "create a tenant" HTTP endpoint beyond the single-use
        // first-run bootstrap — see BootstrapService).
        Tenant tenantA = tenantRepository.save(new Tenant("Tenant A"));
        Tenant tenantB = tenantRepository.save(new Tenant("Tenant B"));
        adminUserRepository.save(
                new AdminUser(tenantA, "owner-a@isolation-test.example", passwordEncoder.encode(PASSWORD), AdminRole.OWNER));
        adminUserRepository.save(
                new AdminUser(tenantB, "owner-b@isolation-test.example", passwordEncoder.encode(PASSWORD), AdminRole.OWNER));

        Application applicationB = applicationRepository.save(new Application(tenantB, "App B"));
        Endpoint endpointB = endpointRepository.save(new Endpoint(applicationB, "https://example.com/hook", "test"));

        String tokenA = login("owner-a@isolation-test.example");
        String tokenB = login("owner-b@isolation-test.example");

        ResponseEntity<String> ownerBFetchesOwn = restTemplate.exchange(
                "/api/v1/admin/endpoints/" + endpointB.getId(), HttpMethod.GET, authed(tokenB), String.class);
        assertThat(ownerBFetchesOwn.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> ownerAFetchesB = restTemplate.exchange(
                "/api/v1/admin/endpoints/" + endpointB.getId(), HttpMethod.GET, authed(tokenA), String.class);
        assertThat(ownerAFetchesB.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // A genuinely nonexistent id must produce the identical 404 shape as a
        // cross-tenant id — that equivalence is what proves no 403 leak exists.
        ResponseEntity<String> randomIdLookup = restTemplate.exchange(
                "/api/v1/admin/endpoints/" + java.util.UUID.randomUUID(), HttpMethod.GET, authed(tokenA), String.class);
        assertThat(randomIdLookup.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ownerAFetchesB.getStatusCode()).isEqualTo(randomIdLookup.getStatusCode());
    }
}
