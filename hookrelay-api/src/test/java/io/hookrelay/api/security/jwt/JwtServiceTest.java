package io.hookrelay.api.security.jwt;

import static org.assertj.core.api.Assertions.assertThat;

import io.hookrelay.common.admin.AdminRole;
import io.hookrelay.common.admin.AdminUser;
import io.hookrelay.common.tenant.Tenant;
import java.lang.reflect.Field;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

    private final JwtService jwtService = new JwtService("test-secret-test-secret-test-secret-test-secret", 3600);

    @Test
    void issuedTokenParsesBackToTheSameTenantAndRole() throws Exception {
        Tenant tenant = new Tenant("Tenant A");
        setId(tenant, UUID.randomUUID());
        AdminUser adminUser = new AdminUser(tenant, "owner@example.com", "hash", AdminRole.DEVELOPER);
        setId(adminUser, UUID.randomUUID());

        String token = jwtService.issue(adminUser);
        Optional<AdminPrincipal> parsed = jwtService.parse(token);

        assertThat(parsed).isPresent();
        assertThat(parsed.get().adminUserId()).isEqualTo(adminUser.getId());
        assertThat(parsed.get().tenantId()).isEqualTo(tenant.getId());
        assertThat(parsed.get().role()).isEqualTo(AdminRole.DEVELOPER);
    }

    @Test
    void garbageTokenDoesNotParse() {
        assertThat(jwtService.parse("not-a-real-jwt")).isEmpty();
    }

    @Test
    void tokenSignedWithADifferentSecretDoesNotParse() throws Exception {
        JwtService other = new JwtService("a-completely-different-secret-value-here", 3600);
        Tenant tenant = new Tenant("Tenant A");
        setId(tenant, UUID.randomUUID());
        AdminUser adminUser = new AdminUser(tenant, "owner@example.com", "hash", AdminRole.OWNER);
        setId(adminUser, UUID.randomUUID());

        String token = other.issue(adminUser);

        assertThat(jwtService.parse(token)).isEmpty();
    }

    private static void setId(Object entity, UUID id) throws Exception {
        Field field = entity.getClass().getDeclaredField("id");
        field.setAccessible(true);
        field.set(entity, id);
    }
}
