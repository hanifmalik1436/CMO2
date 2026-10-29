package com.cmo2.billing.dto;

import jakarta.validation.constraints.NotBlank;

import java.time.Instant;
import java.util.List;

/** Request/response DTOs for the Billing module. */
public final class BillingDtos {

    private BillingDtos() {}

    public record CheckoutRequest(@NotBlank String plan) {}

    public record CheckoutResponse(String url, boolean demo, String plan) {}

    public record PortalResponse(String url) {}

    /** One plan card on the pricing/billing screen. */
    public record PlanOption(
            String code, String name, long priceCents,
            int maxCompanies, int maxConnections, long monthlyAiTokens, int maxSeats,
            boolean current) {}

    /** A single usage meter (used vs. limit). {@code limit} = -1 means unlimited. */
    public record UsageMeter(String key, String label, long used, long limit) {}

    /** The billing dashboard payload. */
    public record BillingSummary(
            String plan,
            String planName,
            String status,
            boolean demo,
            Instant trialEndsAt,
            Instant currentPeriodEnd,
            boolean hasPaymentMethod,
            List<UsageMeter> usage,
            List<PlanOption> plans) {}
}
