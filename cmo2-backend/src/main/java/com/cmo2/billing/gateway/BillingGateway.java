package com.cmo2.billing.gateway;

import com.cmo2.billing.gateway.BillingModels.BillingEvent;
import com.cmo2.billing.gateway.BillingModels.CheckoutSession;
import com.cmo2.tenancy.domain.PlanCode;
import com.cmo2.tenancy.domain.Tenant;

/**
 * A pluggable billing backend, selected by {@code cmo2.billing.provider}. Mirrors the
 * AI provider pattern: the rest of the module talks only to this interface, never to a
 * Stripe SDK directly. The default {@code stub} gateway runs the whole signup -> pay -> use
 * flow with no Stripe account so the "sellable gate" is demonstrable offline.
 */
public interface BillingGateway {

    String name();

    /** True for the stub gateway — enables the in-app "complete demo payment" path. */
    boolean demo();

    /** Start a hosted checkout for {@code plan}; returns where to redirect the browser. */
    CheckoutSession createCheckout(Tenant tenant, PlanCode plan);

    /** Start a customer-portal session (manage card/plan/cancel); returns the URL. */
    String createPortalUrl(Tenant tenant);

    /** Verify + normalize an inbound webhook. {@code signatureValid} reflects HMAC verification. */
    BillingEvent parseWebhook(String payload, String signatureHeader);
}
