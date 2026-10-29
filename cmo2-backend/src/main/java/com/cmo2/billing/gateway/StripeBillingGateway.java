package com.cmo2.billing.gateway;

import com.cmo2.billing.gateway.BillingModels.BillingEvent;
import com.cmo2.billing.gateway.BillingModels.BillingEventType;
import com.cmo2.billing.gateway.BillingModels.CheckoutSession;
import com.cmo2.shared.exceptions.ApiException;
import com.cmo2.tenancy.domain.PlanCode;
import com.cmo2.tenancy.domain.Tenant;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.Stripe;
import com.stripe.model.Event;
import com.stripe.net.Webhook;
import com.stripe.param.checkout.SessionCreateParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

/**
 * Real Stripe gateway (selected by {@code cmo2.billing.provider=stripe}). Uses Stripe-hosted
 * Checkout + Customer Portal, so card data never touches our servers (minimal PCI scope), and
 * verifies inbound webhook signatures with the signing secret per NON-NEGOTIABLE #5. Webhook
 * field extraction goes through the raw JSON tree to stay robust across API versions.
 */
@Component
public class StripeBillingGateway implements BillingGateway {

    private static final Logger log = LoggerFactory.getLogger(StripeBillingGateway.class);

    private final String secretKey;
    private final String webhookSecret;
    private final String successUrl;
    private final String cancelUrl;
    private final String portalReturnUrl;
    private final Map<PlanCode, String> priceIds;
    private final ObjectMapper objectMapper;

    public StripeBillingGateway(
            @Value("${cmo2.billing.secret-key:}") String secretKey,
            @Value("${cmo2.billing.webhook-secret:}") String webhookSecret,
            @Value("${cmo2.billing.success-url:http://localhost:5173/billing}") String successUrl,
            @Value("${cmo2.billing.cancel-url:http://localhost:5173/billing}") String cancelUrl,
            @Value("${cmo2.billing.portal-return-url:http://localhost:5173/billing}") String portalReturnUrl,
            @Value("${cmo2.billing.price-starter:}") String priceStarter,
            @Value("${cmo2.billing.price-pro:}") String pricePro,
            @Value("${cmo2.billing.price-enterprise:}") String priceEnterprise,
            ObjectMapper objectMapper) {
        this.secretKey = secretKey;
        this.webhookSecret = webhookSecret;
        this.successUrl = successUrl;
        this.cancelUrl = cancelUrl;
        this.portalReturnUrl = portalReturnUrl;
        this.priceIds = Map.of(
                PlanCode.STARTER, priceStarter,
                PlanCode.PRO, pricePro,
                PlanCode.ENTERPRISE, priceEnterprise);
        this.objectMapper = objectMapper;
        if (secretKey != null && !secretKey.isBlank()) {
            Stripe.apiKey = secretKey;
        }
    }

    @Override
    public String name() {
        return "stripe";
    }

    @Override
    public boolean demo() {
        return false;
    }

    @Override
    public CheckoutSession createCheckout(Tenant tenant, PlanCode plan) {
        assertConfigured();
        String priceId = priceIds.get(plan);
        if (priceId == null || priceId.isBlank()) {
            throw ApiException.badRequest("No Stripe price configured for plan " + plan);
        }
        try {
            SessionCreateParams.Builder params = SessionCreateParams.builder()
                    .setMode(SessionCreateParams.Mode.SUBSCRIPTION)
                    .setSuccessUrl(successUrl)
                    .setCancelUrl(cancelUrl)
                    .setClientReferenceId(String.valueOf(tenant.getId()))
                    .putMetadata("tenant_id", String.valueOf(tenant.getId()))
                    .putMetadata("plan", plan.name())
                    .addLineItem(SessionCreateParams.LineItem.builder()
                            .setPrice(priceId).setQuantity(1L).build());
            if (tenant.getStripeCustomerId() != null && !tenant.getStripeCustomerId().isBlank()) {
                params.setCustomer(tenant.getStripeCustomerId());
            }
            com.stripe.model.checkout.Session session =
                    com.stripe.model.checkout.Session.create(params.build());
            return new CheckoutSession(session.getUrl(), false, plan);
        } catch (Exception e) {
            log.error("Stripe checkout creation failed: {}", e.getMessage());
            throw new ApiException(org.springframework.http.HttpStatus.BAD_GATEWAY,
                    "Could not start checkout. Please try again.");
        }
    }

    @Override
    public String createPortalUrl(Tenant tenant) {
        assertConfigured();
        if (tenant.getStripeCustomerId() == null || tenant.getStripeCustomerId().isBlank()) {
            throw ApiException.badRequest("No active subscription to manage yet.");
        }
        try {
            com.stripe.param.billingportal.SessionCreateParams params =
                    com.stripe.param.billingportal.SessionCreateParams.builder()
                            .setCustomer(tenant.getStripeCustomerId())
                            .setReturnUrl(portalReturnUrl)
                            .build();
            return com.stripe.model.billingportal.Session.create(params).getUrl();
        } catch (Exception e) {
            log.error("Stripe portal session failed: {}", e.getMessage());
            throw new ApiException(org.springframework.http.HttpStatus.BAD_GATEWAY,
                    "Could not open the billing portal. Please try again.");
        }
    }

    @Override
    public BillingEvent parseWebhook(String payload, String signatureHeader) {
        // Signature verification is the auth boundary for this unauthenticated endpoint.
        try {
            Event event = Webhook.constructEvent(payload, signatureHeader, webhookSecret);
            JsonNode obj = objectMapper.readTree(payload).path("data").path("object");
            String rawType = event.getType();
            return new BillingEvent(
                    mapType(rawType), rawType,
                    text(obj, "customer"),
                    subscriptionId(rawType, obj),
                    text(obj, "client_reference_id"),
                    planFromMetadata(obj),
                    periodEnd(obj),
                    true);
        } catch (Exception e) {
            log.warn("Stripe webhook signature/parse failed: {}", e.getMessage());
            return new BillingEvent(BillingEventType.IGNORED, null, null, null, null, null, null, false);
        }
    }

    private void assertConfigured() {
        if (secretKey == null || secretKey.isBlank()) {
            throw new ApiException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    "Billing is not configured. Set cmo2.billing.secret-key.");
        }
    }

    private BillingEventType mapType(String rawType) {
        if (rawType == null) return BillingEventType.IGNORED;
        return switch (rawType) {
            case "checkout.session.completed" -> BillingEventType.CHECKOUT_COMPLETED;
            case "invoice.payment_failed" -> BillingEventType.PAYMENT_FAILED;
            case "customer.subscription.deleted" -> BillingEventType.SUBSCRIPTION_DELETED;
            default -> BillingEventType.IGNORED;
        };
    }

    /** On a subscription event the id is the object's own id; elsewhere it's the 'subscription' field. */
    private String subscriptionId(String rawType, JsonNode obj) {
        if ("customer.subscription.deleted".equals(rawType)) {
            return text(obj, "id");
        }
        return text(obj, "subscription");
    }

    private PlanCode planFromMetadata(JsonNode obj) {
        JsonNode meta = obj.path("metadata");
        String raw = (meta.isMissingNode() || meta.isNull()) ? null : text(meta, "plan");
        if (raw == null || raw.isBlank()) return null;
        try {
            return PlanCode.valueOf(raw.trim().toUpperCase());
        } catch (Exception e) {
            return null;
        }
    }

    private Instant periodEnd(JsonNode obj) {
        JsonNode v = obj.get("current_period_end");
        return (v == null || v.isNull() || !v.isNumber()) ? null : Instant.ofEpochSecond(v.asLong());
    }

    private String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v.asText();
    }
}
