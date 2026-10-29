package com.cmo2.billing.gateway;

import com.cmo2.tenancy.domain.PlanCode;

import java.time.Instant;

/** Normalized billing types shared across gateways (so the rest of the module is Stripe-agnostic). */
public final class BillingModels {

    private BillingModels() {}

    /** The result of starting a checkout: a URL to send the browser to. {@code demo} = stub flow. */
    public record CheckoutSession(String url, boolean demo, PlanCode plan) {}

    public enum BillingEventType {
        CHECKOUT_COMPLETED,
        PAYMENT_FAILED,
        SUBSCRIPTION_DELETED,
        IGNORED
    }

    /**
     * A normalized webhook event. Gateways map their provider's raw payload into this shape;
     * {@link com.cmo2.billing.service.BillingLifecycleService} drives tenant status from it.
     */
    public record BillingEvent(
            BillingEventType type,
            String rawType,
            String customerId,
            String subscriptionId,
            String clientReferenceId,   // tenant id we set at checkout creation
            PlanCode plan,
            Instant periodEnd,
            boolean signatureValid) {}
}
