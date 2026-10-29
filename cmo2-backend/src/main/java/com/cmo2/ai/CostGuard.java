package com.cmo2.ai;

import com.cmo2.shared.exceptions.ApiException;
import com.cmo2.shared.security.TenantContext;
import com.cmo2.tenancy.domain.PlanCode;
import com.cmo2.tenancy.domain.Tenant;
import com.cmo2.tenancy.repository.TenantRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The circuit breaker behind NON-NEGOTIABLE RULE #8 ("AI HAS A BUDGET"). Before any
 * AI call, asserts the tenant is within its plan's monthly token ceiling; refuses
 * with HTTP 429 once the ceiling is hit. Protects margins and prevents runaway loops.
 */
@Service
public class CostGuard {

    private final TenantRepository tenantRepository;
    private final TokenAccountant tokenAccountant;

    public CostGuard(TenantRepository tenantRepository, TokenAccountant tokenAccountant) {
        this.tenantRepository = tenantRepository;
        this.tokenAccountant = tokenAccountant;
    }

    @Transactional(readOnly = true)
    public void assertWithinBudget() {
        Long tenantId = TenantContext.requireTenantId();
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> ApiException.notFound("Tenant not found"));
        long ceiling = tenant.getPlanCode() == null
                ? PlanCode.STARTER.monthlyAiTokens : tenant.getPlanCode().monthlyAiTokens;

        long used = tokenAccountant.currentPeriodTokens();
        if (used >= ceiling) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                    "Monthly AI token budget reached for your plan (" + tenant.getPlanCode()
                            + "). Upgrade to continue generating.");
        }
    }

    @Transactional(readOnly = true)
    public long ceilingForCurrentTenant() {
        Long tenantId = TenantContext.requireTenantId();
        return tenantRepository.findById(tenantId)
                .map(t -> t.getPlanCode() == null ? PlanCode.STARTER.monthlyAiTokens : t.getPlanCode().monthlyAiTokens)
                .orElse(PlanCode.STARTER.monthlyAiTokens);
    }

    /** AI tokens consumed by the current tenant this billing period (for the usage dashboard). */
    @Transactional(readOnly = true)
    public long usedTokensForCurrentTenant() {
        return tokenAccountant.currentPeriodTokens();
    }
}
