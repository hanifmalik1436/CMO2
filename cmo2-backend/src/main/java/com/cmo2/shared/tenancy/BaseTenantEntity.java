package com.cmo2.shared.tenancy;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;

import java.time.Instant;

/**
 * Base class for every tenant-owned entity.
 *
 * <p>Two layers of defense are wired in here so a developer cannot accidentally
 * leak data across tenants:
 * <ul>
 *   <li>{@code @FilterDef}/{@code @Filter} — Hibernate appends
 *       {@code WHERE tenant_id = :tenantId} to every SELECT once the filter is
 *       enabled (see {@link TenantFilterAspect}).</li>
 *   <li>{@link TenantEntityListener} — auto-stamps {@code tenant_id} on INSERT
 *       from {@link com.cmo2.shared.security.TenantContext}.</li>
 * </ul>
 */
@MappedSuperclass
@Getter
@Setter
@EntityListeners(TenantEntityListener.class)
@FilterDef(
        name = BaseTenantEntity.TENANT_FILTER,
        parameters = @ParamDef(name = "tenantId", type = Long.class)
)
@Filter(name = BaseTenantEntity.TENANT_FILTER, condition = "tenant_id = :tenantId")
public abstract class BaseTenantEntity {

    public static final String TENANT_FILTER = "tenantFilter";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Long tenantId;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;
}
