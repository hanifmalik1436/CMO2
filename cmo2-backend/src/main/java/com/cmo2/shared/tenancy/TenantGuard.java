package com.cmo2.shared.tenancy;

import com.cmo2.shared.security.TenantContext;

import java.util.Objects;
import java.util.Optional;

/**
 * Defense-in-depth for by-primary-key lookups.
 *
 * <p>Hibernate's tenant {@code @Filter} is applied to <em>queries</em> (the derived
 * {@code findAll…}/{@code findBy…} finders), but NOT to {@code EntityManager.find()} —
 * which is what Spring Data's {@code findById} uses. So a raw {@code findById} can return
 * another tenant's row. Wrap such lookups with {@link #owned(Optional)} so a cross-tenant
 * id is treated as "not found".
 */
public final class TenantGuard {

    private TenantGuard() {}

    /** Keeps the entity only if it belongs to the current tenant; otherwise empty. */
    public static <T extends BaseTenantEntity> Optional<T> owned(Optional<T> entity) {
        Long current = TenantContext.getTenantId();
        return entity.filter(e -> Objects.equals(e.getTenantId(), current));
    }
}
