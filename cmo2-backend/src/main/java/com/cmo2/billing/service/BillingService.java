package com.cmo2.billing.service;

import com.cmo2.billing.Plans;
import com.cmo2.billing.dto.BillingDtos.BillingSummary;
import com.cmo2.billing.dto.BillingDtos.CheckoutResponse;
import com.cmo2.billing.dto.BillingDtos.PlanOption;
import com.cmo2.billing.dto.BillingDtos.PortalResponse;
import com.cmo2.billing.dto.BillingDtos.UsageMeter;
import com.cmo2.billing.gateway.BillingGateway;
import com.cmo2.billing.gateway.BillingModels.CheckoutSession;
import com.cmo2.billing.service.PlanLimitGuard.Usage;
import com.cmo2.shared.exceptions.ApiException;
import com.cmo2.shared.security.TenantContext;
import com.cmo2.tenancy.domain.PlanCode;
import com.cmo2.tenancy.domain.Tenant;
import com.cmo2.tenancy.repository.TenantRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The Billing module's application service: surfaces the plan/usage summary, starts hosted
 * Checkout and Customer-Portal sessions, and (in demo mode only) simulates a successful
 * payment so the sellable flow is exercisable without a Stripe account. All state changes
 * flow through {@link BillingLifecycleService}. Authorization is enforced here.
 */
@Service
public class BillingService {

    private final Map<String, BillingGateway> gateways;
    private final String providerName;
    private final TenantRepository tenants;
    private final BillingLifecycleService lifecycle;
    private final PlanLimitGuard planLimitGuard;

    public BillingService(List<BillingGateway> gatewayBeans,
                          @Value("${cmo2.billing.provider:stub}") String providerName,
                          TenantRepository tenants,
                          BillingLifecycleService lifecycle,
                          PlanLimitGuard planLimitGuard) {
        this.gateways = gatewayBeans.stream().collect(Collectors.toMap(BillingGateway::name, Function.identity()));
        this.providerName = providerName;
        this.tenants = tenants;
        this.lifecycle = lifecycle;
        this.planLimitGuard = planLimitGuard;
    }

    private BillingGateway gateway() {
        return gateways.getOrDefault(providerName, gateways.get("stub"));
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','STRATEGIST','ANALYST','VIEWER')")
    public BillingSummary summary() {
        Tenant tenant = currentTenant();
        Usage u = planLimitGuard.currentUsage();
        List<UsageMeter> meters = List.of(
                new UsageMeter("companies", "Companies", u.companies(), unlimited(u.companiesMax())),
                new UsageMeter("connections", "Platform connections", u.connections(), unlimited(u.connectionsMax())),
                new UsageMeter("seats", "Team seats", u.seats(), unlimited(u.seatsMax())),
                new UsageMeter("ai_tokens", "AI tokens (this month)", u.aiTokens(), u.aiTokensMax()));

        PlanCode current = tenant.getPlanCode() == null ? PlanCode.STARTER : tenant.getPlanCode();
        List<PlanOption> plans = java.util.Arrays.stream(PlanCode.values())
                .map(p -> new PlanOption(
                        p.name(), Plans.displayName(p), Plans.monthlyPriceCents(p),
                        p.maxCompanies, p.maxConnections, p.monthlyAiTokens, p.maxSeats,
                        p == current))
                .toList();

        return new BillingSummary(
                current.name(), Plans.displayName(current), tenant.getStatus().name(),
                gateway().demo(), tenant.getTrialEndsAt(), tenant.getCurrentPeriodEnd(),
                tenant.getStripeCustomerId() != null, meters, plans);
    }

    @Transactional
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    public CheckoutResponse startCheckout(String planRaw) {
        Tenant tenant = currentTenant();
        PlanCode plan = parsePlan(planRaw);
        CheckoutSession session = gateway().createCheckout(tenant, plan);
        return new CheckoutResponse(session.url(), session.demo(), plan.name());
    }

    @Transactional
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    public PortalResponse openPortal() {
        return new PortalResponse(gateway().createPortalUrl(currentTenant()));
    }

    /**
     * Demo-only: completes an upgrade locally (same path a Stripe {@code checkout.session.completed}
     * webhook would drive). Rejected when a real billing provider is configured.
     */
    @Transactional
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    public BillingSummary simulateActivation(String planRaw) {
        if (!gateway().demo()) {
            throw ApiException.badRequest("Demo activation is disabled — a live billing provider is configured.");
        }
        PlanCode plan = parsePlan(planRaw);
        Long tenantId = TenantContext.requireTenantId();
        lifecycle.activate(tenantId, plan, "cus_demo_" + tenantId, "sub_demo_" + tenantId, null);
        return summary();
    }

    private Tenant currentTenant() {
        Long tenantId = TenantContext.requireTenantId();
        return tenants.findById(tenantId).orElseThrow(() -> ApiException.notFound("Tenant not found"));
    }

    private PlanCode parsePlan(String raw) {
        if (raw == null || raw.isBlank()) {
            throw ApiException.badRequest("Plan is required");
        }
        try {
            return PlanCode.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Unknown plan: " + raw);
        }
    }

    /** Integer.MAX_VALUE limits render as -1 ("unlimited") to the UI. */
    private long unlimited(int max) {
        return max == Integer.MAX_VALUE ? -1 : max;
    }
}
