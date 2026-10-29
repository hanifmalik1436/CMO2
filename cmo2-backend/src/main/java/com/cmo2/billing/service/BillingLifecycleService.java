package com.cmo2.billing.service;

import com.cmo2.billing.gateway.BillingModels.BillingEvent;
import com.cmo2.shared.audit.AuditService;
import com.cmo2.tenancy.domain.PlanCode;
import com.cmo2.tenancy.domain.Tenant;
import com.cmo2.tenancy.domain.TenantStatus;
import com.cmo2.tenancy.repository.TenantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * The single source of truth for subscription state transitions (active → past_due →
 * suspended/canceled). Driven by both the Stripe webhook and the demo "simulate" path, so
 * the lifecycle behaves identically with or without a real Stripe account. Runs with NO
 * tenant context on the webhook path — it resolves the tenant from the normalized event.
 */
@Service
public class BillingLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(BillingLifecycleService.class);
    private static final int DEFAULT_PERIOD_DAYS = 30;

    private final TenantRepository tenants;
    private final AuditService audit;

    public BillingLifecycleService(TenantRepository tenants, AuditService audit) {
        this.tenants = tenants;
        this.audit = audit;
    }

    /** Apply a normalized webhook event, resolving the tenant from its ids. Returns the tenant id, or null. */
    @Transactional
    public Long apply(BillingEvent event) {
        Tenant tenant = resolveTenant(event);
        if (tenant == null) {
            log.warn("Billing event {} could not be matched to a tenant; ignoring", event.rawType());
            return null;
        }
        switch (event.type()) {
            case CHECKOUT_COMPLETED ->
                    activate(tenant, event.plan(), event.customerId(), event.subscriptionId(), event.periodEnd());
            case PAYMENT_FAILED -> markPastDue(tenant);
            case SUBSCRIPTION_DELETED -> cancel(tenant);
            case IGNORED -> log.debug("Ignoring billing event {}", event.rawType());
        }
        return tenant.getId();
    }

    /** Activate (or renew) a paid subscription. */
    @Transactional
    public Tenant activate(Long tenantId, PlanCode plan, String customerId, String subscriptionId, Instant periodEnd) {
        return activate(requireTenant(tenantId), plan, customerId, subscriptionId, periodEnd);
    }

    private Tenant activate(Tenant tenant, PlanCode plan, String customerId, String subscriptionId, Instant periodEnd) {
        if (plan != null) {
            tenant.setPlanCode(plan);
        }
        if (customerId != null && !customerId.isBlank()) {
            tenant.setStripeCustomerId(customerId);
        }
        if (subscriptionId != null && !subscriptionId.isBlank()) {
            tenant.setStripeSubscriptionId(subscriptionId);
        }
        tenant.setCurrentPeriodEnd(periodEnd != null
                ? periodEnd : Instant.now().plus(DEFAULT_PERIOD_DAYS, ChronoUnit.DAYS));
        tenant.setStatus(TenantStatus.ACTIVE);
        Tenant saved = tenants.save(tenant);
        audit.recordFor(saved.getId(), null, "SUBSCRIPTION_ACTIVATED", "Tenant",
                String.valueOf(saved.getId()), "Plan " + saved.getPlanCode());
        log.info("Tenant {} subscription ACTIVE on {}", saved.getId(), saved.getPlanCode());
        return saved;
    }

    @Transactional
    public void markPastDue(Tenant tenant) {
        tenant.setStatus(TenantStatus.PAST_DUE);
        tenants.save(tenant);
        audit.recordFor(tenant.getId(), null, "SUBSCRIPTION_PAST_DUE", "Tenant",
                String.valueOf(tenant.getId()), "Payment failed; entered grace period");
        log.info("Tenant {} subscription PAST_DUE", tenant.getId());
    }

    @Transactional
    public void cancel(Tenant tenant) {
        tenant.setStatus(TenantStatus.CANCELED);
        tenant.setCurrentPeriodEnd(null);
        tenants.save(tenant);
        audit.recordFor(tenant.getId(), null, "SUBSCRIPTION_CANCELED", "Tenant",
                String.valueOf(tenant.getId()), "Subscription ended");
        log.info("Tenant {} subscription CANCELED", tenant.getId());
    }

    private Tenant resolveTenant(BillingEvent event) {
        if (event.clientReferenceId() != null && !event.clientReferenceId().isBlank()) {
            try {
                Tenant t = tenants.findById(Long.parseLong(event.clientReferenceId().trim())).orElse(null);
                if (t != null) return t;
            } catch (NumberFormatException ignored) {
                // fall through to id-based lookups
            }
        }
        if (event.subscriptionId() != null) {
            Tenant t = tenants.findByStripeSubscriptionId(event.subscriptionId()).orElse(null);
            if (t != null) return t;
        }
        if (event.customerId() != null) {
            return tenants.findByStripeCustomerId(event.customerId()).orElse(null);
        }
        return null;
    }

    private Tenant requireTenant(Long tenantId) {
        return tenants.findById(tenantId)
                .orElseThrow(() -> new IllegalStateException("Tenant " + tenantId + " not found"));
    }
}
