package io.hookrelay.api.security.tenant;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.hibernate.Session;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The single place tenant isolation is enforced. Registered to run
 * immediately after the API-key / JWT authentication filter on every
 * security filter chain, it binds one {@link EntityManager} to the request
 * thread (the same mechanism Spring's own Open-Session-In-View filter uses)
 * and, if authentication resolved a tenant, enables the {@code tenantFilter}
 * Hibernate filter on it.
 *
 * From this point on every repository call made during the request —
 * regardless of which service or controller makes it — transparently sees
 * only that tenant's rows for every entity carrying {@code @Filter(name =
 * "tenantFilter", ...)}. Services and repositories need no tenant-awareness
 * of their own; a lookup by primary key belonging to a different tenant
 * simply isn't visible, which is what turns cross-tenant access into a plain
 * 404 instead of a 403 (see EndpointService / EndpointController).
 *
 * Deliberately a Filter rather than a HandlerInterceptor: Filter ordering in
 * a Spring Security chain is fully explicit (addFilterAfter), whereas
 * ordering between HandlerInterceptors from different auto-configuration
 * sources is not something application code controls reliably. Spring Boot's
 * own Open-Session-In-View is disabled (spring.jpa.open-in-view=false) so
 * this filter is the only thing binding an EntityManager to the request.
 *
 * Enabling the filter here is necessary but not sufficient: Hibernate does
 * not apply enabled filters to a load by primary key, only to queries, so
 * see {@code io.hookrelay.common.tenancy.TenantScopedRepositoryImpl} for the
 * other half of this guarantee.
 */
public class TenantScopingFilter extends OncePerRequestFilter {

    private final EntityManagerFactory entityManagerFactory;

    public TenantScopingFilter(EntityManagerFactory entityManagerFactory) {
        this.entityManagerFactory = entityManagerFactory;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        EntityManager entityManager = entityManagerFactory.createEntityManager();
        TransactionSynchronizationManager.bindResource(entityManagerFactory, new EntityManagerHolder(entityManager));
        try {
            TenantContext.current().ifPresent(tenantId -> enableFilter(entityManager, tenantId));
            chain.doFilter(request, response);
        } finally {
            TransactionSynchronizationManager.unbindResource(entityManagerFactory);
            EntityManagerFactoryUtils.closeEntityManager(entityManager);
        }
    }

    private void enableFilter(EntityManager entityManager, UUID tenantId) {
        entityManager.unwrap(Session.class)
                .enableFilter("tenantFilter")
                .setParameter("tenantId", tenantId);
    }
}
