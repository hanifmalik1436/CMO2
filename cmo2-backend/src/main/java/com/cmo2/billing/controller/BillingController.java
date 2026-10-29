package com.cmo2.billing.controller;

import com.cmo2.billing.dto.BillingDtos.BillingSummary;
import com.cmo2.billing.dto.BillingDtos.CheckoutRequest;
import com.cmo2.billing.dto.BillingDtos.CheckoutResponse;
import com.cmo2.billing.dto.BillingDtos.PortalResponse;
import com.cmo2.billing.service.BillingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/billing")
@Tag(name = "Billing")
public class BillingController {

    private final BillingService billingService;

    public BillingController(BillingService billingService) {
        this.billingService = billingService;
    }

    @GetMapping("/summary")
    @Operation(summary = "Current plan, status, usage vs. limits, and available plans")
    public BillingSummary summary() {
        return billingService.summary();
    }

    @PostMapping("/checkout")
    @Operation(summary = "Start a hosted checkout to subscribe/upgrade; returns a redirect URL")
    public CheckoutResponse checkout(@Valid @RequestBody CheckoutRequest request) {
        return billingService.startCheckout(request.plan());
    }

    @PostMapping("/portal")
    @Operation(summary = "Open the Stripe Customer Portal to manage card/plan/cancellation")
    public PortalResponse portal() {
        return billingService.openPortal();
    }

    @PostMapping("/simulate-activation")
    @Operation(summary = "Demo only: complete an upgrade locally (same path a Stripe webhook drives)")
    public BillingSummary simulateActivation(@Valid @RequestBody CheckoutRequest request) {
        return billingService.simulateActivation(request.plan());
    }
}
