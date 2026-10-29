package com.cmo2.billing.controller;

import com.cmo2.billing.service.StripeWebhookService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inbound Stripe webhooks. Unauthenticated (under /api/webhooks/**, permitAll); the
 * signature is the auth boundary. Always returns 200 quickly so Stripe doesn't retry.
 */
@RestController
@RequestMapping("/api/webhooks")
@Tag(name = "Webhooks")
public class StripeWebhookController {

    private final StripeWebhookService webhookService;

    public StripeWebhookController(StripeWebhookService webhookService) {
        this.webhookService = webhookService;
    }

    @PostMapping("/stripe")
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "Receive a Stripe webhook (signature-verified, persisted raw, drives subscription lifecycle)")
    public void receive(@RequestBody(required = false) String body,
                        @RequestHeader(value = "Stripe-Signature", required = false) String signature) {
        webhookService.receive(body == null ? "" : body, signature);
    }
}
