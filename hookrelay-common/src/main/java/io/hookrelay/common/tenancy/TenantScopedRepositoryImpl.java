package io.hookrelay.common.tenancy;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import java.util.Optional;
import org.springframework.data.jpa.repository.support.JpaEntityInformation;
import org.springframework.data.jpa.repository.support.SimpleJpaRepository;

/**
 * Registered project-wide as the default Spring Data JPA repository base
 * class (see HookrelayApiApplication's {@code @EnableJpaRepositories}) so
 * that every repository's {@code findById}/{@code existsById} — the most
 * common way an id supplied by a caller turns into an entity — is safe by
 * construction.
 *
 * <p>This exists because of a long-standing Hibernate limitation: the
 * enabled {@code tenantFilter} filter (see TenantScopingFilter) is applied
 * to HQL/Criteria queries, but NOT to {@code EntityManager.find()} — which is
 * exactly what {@link SimpleJpaRepository#findById} uses internally as an
 * optimization. Left alone, that means the one method every controller
 * naturally reaches for for a by-id lookup would silently ignore tenant
 * isolation. Overriding it here to go through a Criteria query instead
 * closes that gap in the single place every repository inherits from,
 * rather than requiring each one to remember to avoid {@code findById}.
 */
public class TenantScopedRepositoryImpl<T, ID> extends SimpleJpaRepository<T, ID> {

    private final JpaEntityInformation<T, ?> entityInformation;
    private final EntityManager entityManager;

    public TenantScopedRepositoryImpl(JpaEntityInformation<T, ?> entityInformation, EntityManager entityManager) {
        super(entityInformation, entityManager);
        this.entityInformation = entityInformation;
        this.entityManager = entityManager;
    }

    @Override
    public Optional<T> findById(ID id) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<T> query = cb.createQuery(entityInformation.getJavaType());
        Root<T> root = query.from(entityInformation.getJavaType());
        query.where(cb.equal(root.get(entityInformation.getIdAttribute().getName()), id));
        return entityManager.createQuery(query).getResultStream().findFirst();
    }

    @Override
    public boolean existsById(ID id) {
        return findById(id).isPresent();
    }
}
