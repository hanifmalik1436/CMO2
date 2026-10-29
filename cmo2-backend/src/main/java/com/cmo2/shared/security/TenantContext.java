package com.cmo2.shared.security;

/**
 * Holds the current request's tenant and user identity in a ThreadLocal.
 *
 * Populated by {@link com.cmo2.shared.security.JwtAuthFilter} from the verified JWT,
 * read by the Hibernate tenant filter / entity listener, and cleared at request exit.
 *
 * NON-NEGOTIABLE: tenant_id is ALWAYS taken from here (derived from the signed token),
 * never from client input.
 */
public final class TenantContext {

    private record Identity(Long tenantId, Long userId) {}

    private static final ThreadLocal<Identity> HOLDER = new ThreadLocal<>();

    private TenantContext() {}

    public static void set(Long tenantId, Long userId) {
        HOLDER.set(new Identity(tenantId, userId));
    }

    public static boolean hasTenant() {
        return HOLDER.get() != null && HOLDER.get().tenantId() != null;
    }

    public static Long getTenantId() {
        Identity id = HOLDER.get();
        return id == null ? null : id.tenantId();
    }

    public static Long getUserId() {
        Identity id = HOLDER.get();
        return id == null ? null : id.userId();
    }

    /** Throws if no tenant is bound — use where a tenant is structurally required. */
    public static Long requireTenantId() {
        Long tenantId = getTenantId();
        if (tenantId == null) {
            throw new IllegalStateException("No tenant bound to the current request context");
        }
        return tenantId;
    }

    public static void clear() {
        HOLDER.remove();
    }
}
