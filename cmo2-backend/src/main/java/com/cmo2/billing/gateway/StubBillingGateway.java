package com.cmo2.billing.gateway;

import com.cmo2.billing.gateway.BillingModels.BillingEvent;
import com.cmo2.billing.gateway.BillingModels.BillingEventType;
import com.cmo2.billing.gateway.BillingModels.CheckoutSession;
import com.cmo2.tenancy.domain.PlanCode;
import com.cmo2.tenancy.domain.Tenant;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Default billing gateway: no Stripe account required. "Checkout" returns the frontend
 * billing page (the UI then completes the upgrade via the in-app demo-payment path), so a
 * stranger can sign up, "pay", and use the product end-to-end offline — the Phase 7
 * sellable gate, demonstrable without secrets. Set {@code cmo2.billing.provider=stripe}
 * for real hosted Checkout/Portal + webhooks.
 */
@Component
public class StubBillingGateway implements BillingGateway {

    private static final Logger log = LoggerFactory.getLogger(StubBillingGateway.class);

    private final String successUrl;
    private final ObjectMapper objectMapper;

    public StubBillingGateway(@Value("${cmo2.billing.success-url:http://localhost:5173/billing}") String successUrl,
                              ObjectMapper objectMapper) {
        this.successUrl = successUrl;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "stub";
    }

    @Override
    public boolean demo() {
        return true;
    }

    @Override
    public CheckoutSession createCheckout(Tenant tenant, PlanCode plan) {
        String sep = successUrl.contains("?") ? "&" : "?";
        String url = successUrl + sep + "demo_plan=" + plan.name();
        log.info("[billing:stub] demo checkout for tenant {} -> {}", tenant.getId(), plan);
        return new CheckoutSession(url, true, plan);
    }

    @Override
    public String createPortalUrl(Tenant tenant) {
        return successUrl; // demo: "manage billing" just returns to the billing page
    }

    /**
     * Stub webhooks have no signing secret; a locally-posted JSON event is trusted. Shape:
     * {@code {"type":"checkout.session.completed","customerId":"...","subscriptionId":"...","plan":"PRO"}}.
     */
    @Override
    public BillingEvent parseWebhook(String payload, String signatureHeader) {
        try {
            JsonNode root = objectMapper.readTree(payload == null ? "" : payload);
            String rawType = text(root, "type");
            PlanCode plan = parsePlan(text(root, "plan"));
            return new BillingEvent(
                    mapType(rawType), rawType,
                    text(root, "customerId"), text(root, "subscriptionId"),
                    text(root, "clientReferenceId"), plan, null, true);
        } catch (Exception e) {
            log.warn("[billing:stub] unparseable webhook: {}", e.getMessage());
            return new BillingEvent(BillingEventType.IGNORED, null, null, null, null, null, null, true);
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

    private PlanCode parsePlan(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return PlanCode.valueOf(raw.trim().toUpperCase());
        } catch (Exception e) {
            return null;
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v.asText();
    }
}
