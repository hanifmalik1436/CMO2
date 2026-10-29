package com.cmo2.billing.service;

import com.cmo2.ai.CostGuard;
import com.cmo2.auth.domain.UserStatus;
import com.cmo2.auth.repository.UserRepository;
import com.cmo2.billing.Plans;
import com.cmo2.integrations.domain.ConnectionStatus;
import com.cmo2.integrations.repository.PlatformConnectionRepository;
import com.cmo2.shared.billing.PlanLimits;
import com.cmo2.shared.exceptions.ApiException;
import com.cmo2.shared.exceptions.PlanLimitException;
import com.cmo2.shared.security.TenantContext;
import com.cmo2.tenancy.domain.CompanyStatus;
import com.cmo2.tenancy.domain.PlanCode;
import com.cmo2.tenancy.domain.Tenant;
import com.cmo2.tenancy.repository.CompanyRepository;
import com.cmo2.tenancy.repository.TenantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Enforces the current tenant's plan quotas at the service layer (NON-NEGOTIABLE: limits
 * are enforced server-side, not in the UI). Each {@code assertCanAdd…} throws a
 * {@link PlanLimitException} (HTTP 402 + structured upgrade hint) when a quota is reached.
 * AI-token budget is owned by {@link CostGuard}; this surfaces it read-only for the summary.
 */
@Service
public class PlanLimitGuard implements PlanLimits {

    private final TenantRepository tenants;
    private final CompanyRepository companies;
    private final PlatformConnectionRepository connections;
    private final UserRepository users;
    private final CostGuard costGuard;

    public PlanLimitGuard(TenantRepository tenants, CompanyRepository companies,
                          PlatformConnectionRepository connections, UserRepository users,
                          CostGuard costGuard) {
        this.tenants = tenants;
        this.companies = companies;
        this.connections = connections;
        this.users = users;
        this.costGuard = costGuard;
    }

    /** A snapshot of usage vs. plan limits, for the billing dashboard. */
    public record Usage(
            int companies, int companiesMax,
            int connections, int connectionsMax,
            int seats, int seatsMax,
            long aiTokens, long aiTokensMax) {}

    public PlanCode currentPlan() {
        Long tenantId = TenantContext.requireTenantId();
        return tenants.findById(tenantId)
                .map(t -> t.getPlanCode() == null ? PlanCode.STARTER : t.getPlanCode())
                .orElse(PlanCode.STARTER);
    }

    @Transactional(readOnly = true)
    public Usage currentUsage() {
        Long tenantId = TenantContext.requireTenantId();
        PlanCode plan = currentPlan();
        int companyCount = (int) companies.countByStatus(CompanyStatus.ACTIVE);
        int connectionCount = (int) connections.countByStatusNot(ConnectionStatus.DISCONNECTED);
        int seatCount = (int) users.countByTenantIdAndStatusNot(tenantId, UserStatus.DISABLED);
        return new Usage(
                companyCount, plan.maxCompanies,
                connectionCount, plan.maxConnections,
                seatCount, plan.maxSeats,
                costGuard.usedTokensForCurrentTenant(), costGuard.ceilingForCurrentTenant());
    }

    @Transactional(readOnly = true)
    public void assertCanAddCompany() {
        PlanCode plan = currentPlan();
        long count = companies.countByStatus(CompanyStatus.ACTIVE);
        if (count >= plan.maxCompanies) {
            throw limit("companies", count, plan.maxCompanies, plan,
                    "You've reached your plan's company limit (" + plan.maxCompanies + ").");
        }
    }

    @Transactional(readOnly = true)
    public void assertCanAddConnection() {
        PlanCode plan = currentPlan();
        long count = connections.countByStatusNot(ConnectionStatus.DISCONNECTED);
        if (count >= plan.maxConnections) {
            throw limit("connections", count, plan.maxConnections, plan,
                    "You've reached your plan's platform-connection limit (" + plan.maxConnections + ").");
        }
    }

    @Transactional(readOnly = true)
    public void assertCanAddSeat() {
        Long tenantId = TenantContext.requireTenantId();
        PlanCode plan = currentPlan();
        long count = users.countByTenantIdAndStatusNot(tenantId, UserStatus.DISABLED);
        if (count >= plan.maxSeats) {
            throw limit("seats", count, plan.maxSeats, plan,
                    "You've reached your plan's team-seat limit (" + plan.maxSeats + ").");
        }
    }

    private PlanLimitException limit(String type, long current, long max, PlanCode plan, String base) {
        PlanCode next = Plans.nextTier(plan);
        String msg = base + (next == null
                ? " Contact us about a custom plan."
                : " Upgrade to " + Plans.displayName(next) + " to add more.");
        return new PlanLimitException(msg, type, current, max,
                Plans.displayName(plan), next == null ? null : Plans.displayName(next));
    }

    /** Convenience for callers that prefer to surface a generic message. */
    public Tenant requireTenant() {
        Long tenantId = TenantContext.requireTenantId();
        return tenants.findById(tenantId).orElseThrow(() -> ApiException.notFound("Tenant not found"));
    }
}
