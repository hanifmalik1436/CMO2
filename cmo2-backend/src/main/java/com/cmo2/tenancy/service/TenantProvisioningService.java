package com.cmo2.tenancy.service;

import com.cmo2.tenancy.domain.PlanCode;
import com.cmo2.tenancy.domain.Tenant;
import com.cmo2.tenancy.domain.TenantStatus;
import com.cmo2.tenancy.repository.TenantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Creates new tenants. Called by the auth module during self-serve registration —
 * keeping tenant creation behind a tenancy-module service preserves the module boundary
 * (auth doesn't touch the Tenant entity directly).
 */
@Service
public class TenantProvisioningService {

    private static final int TRIAL_DAYS = 14;

    private final TenantRepository tenantRepository;

    public TenantProvisioningService(TenantRepository tenantRepository) {
        this.tenantRepository = tenantRepository;
    }

    @Transactional
    public Tenant createTrialTenant(String organizationName) {
        Tenant tenant = new Tenant();
        tenant.setName(organizationName);
        tenant.setStatus(TenantStatus.TRIAL);
        tenant.setPlanCode(PlanCode.STARTER);
        tenant.setTrialEndsAt(Instant.now().plus(TRIAL_DAYS, ChronoUnit.DAYS));
        return tenantRepository.save(tenant);
    }
}
