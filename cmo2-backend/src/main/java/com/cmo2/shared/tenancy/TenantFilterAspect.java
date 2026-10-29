package com.cmo2.shared.tenancy;

import com.cmo2.shared.security.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.hibernate.Session;
import org.springframework.stereotype.Component;

/**
 * Enables Hibernate's tenant {@link org.hibernate.annotations.Filter} on the current
 * persistence-context session before any repository call, whenever a tenant is bound.
 *
 * <p>Because every tenant-owned read goes through a Spring Data repository inside a
 * transactional service, enabling the filter here guarantees {@code WHERE tenant_id = ?}
 * is appended to those queries — developers physically cannot forget the clause.
 *
 * <p>When no tenant is bound (pre-authentication lookups: login by email, token
 * validation) the filter is left disabled so the auth bootstrap can resolve the user.
 */
@Aspect
@Component
public class TenantFilterAspect {

    @PersistenceContext
    private EntityManager entityManager;

    @Before("execution(* org.springframework.data.repository.Repository+.*(..))")
    public void enableTenantFilter() {
        if (!TenantContext.hasTenant()) {
            return;
        }
        Session session = entityManager.unwrap(Session.class);
        session.enableFilter(BaseTenantEntity.TENANT_FILTER)
                .setParameter("tenantId", TenantContext.getTenantId());
    }
}
