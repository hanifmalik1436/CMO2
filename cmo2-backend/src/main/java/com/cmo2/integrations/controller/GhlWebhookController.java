package com.cmo2.integrations.controller;

import com.cmo2.integrations.service.WebhookService;
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
 * Inbound GoHighLevel webhooks. Unauthenticated (no JWT) — authenticity is established
 * by Ed25519 signature verification inside {@link WebhookService} (see
 * {@link com.cmo2.integrations.gohighlevel.GhlSignatureVerifier}). Always returns 200
 * quickly so the sender doesn't retry; the raw event is persisted for (re)processing.
 */
@RestController
@RequestMapping("/api/webhooks")
@Tag(name = "Webhooks")
public class GhlWebhookController {

    private final WebhookService webhookService;

    public GhlWebhookController(WebhookService webhookService) {
        this.webhookService = webhookService;
    }

    @PostMapping("/gohighlevel")
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "Receive a GoHighLevel webhook (Ed25519-signature-verified, persisted raw)")
    public void receive(@RequestBody(required = false) String body,
                        @RequestHeader(value = "X-GHL-Signature", required = false) String signature) {
        webhookService.receiveGoHighLevel(body == null ? "" : body, signature);
    }
}
