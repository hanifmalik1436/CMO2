package com.cmo2.tenancy.domain;

import com.cmo2.shared.tenancy.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * A brand managed under a tenant (the Multi-Company module). Tenant-owned, so it
 * inherits automatic tenant_id stamping + query scoping from {@link BaseTenantEntity}.
 */
@Entity
@Table(name = "companies")
@Getter
@Setter
public class Company extends BaseTenantEntity {

    @Column(nullable = false, length = 160)
    private String name;

    @Column(length = 120)
    private String industry;

    @Column(name = "brand_voice", length = 255)
    private String brandVoice;

    @Column(length = 255)
    private String website;

    /** IANA timezone of the customer's GHL sub-account, e.g. "Europe/London". KPI days follow it. */
    @Column(length = 64)
    private String timezone;

    /** The GHL sub-account (location) this customer is linked to; unique across all tenants. */
    @Column(name = "ghl_location_id", length = 64, unique = true)
    private String ghlLocationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private CompanyStatus status = CompanyStatus.ACTIVE;

    /** 0..5 — the 5-step onboarding wizard's persisted state (resumable across refresh). */
    @Column(name = "onboarding_step", nullable = false)
    private int onboardingStep = 0;
}
