package com.cmo2.shared.tenancy;

import com.cmo2.shared.security.TenantContext;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;

import java.time.Instant;

/**
 * Auto-stamps {@code tenant_id} (from {@link TenantContext}) and timestamps on
 * tenant-owned entities. The client never supplies tenant_id.
 */
public class TenantEntityListener {

    @PrePersist
    public void prePersist(BaseTenantEntity entity) {
        Instant now = Instant.now();
        if (entity.getCreatedAt() == null) {
            entity.setCreatedAt(now);
        }
        entity.setUpdatedAt(now);

        if (entity.getTenantId() == null && TenantContext.hasTenant()) {
            entity.setTenantId(TenantContext.getTenantId());
        }
        if (entity.getTenantId() == null) {
            throw new IllegalStateException(
                    "Refusing to persist " + entity.getClass().getSimpleName()
                            + " without a tenant_id (no TenantContext bound).");
        }
    }

    @PreUpdate
    public void preUpdate(BaseTenantEntity entity) {
        entity.setUpdatedAt(Instant.now());
    }
}
