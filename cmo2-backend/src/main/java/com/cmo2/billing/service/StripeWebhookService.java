package com.cmo2.billing.service;

import com.cmo2.billing.gateway.BillingGateway;
import com.cmo2.billing.gateway.BillingModels.BillingEvent;
import com.cmo2.billing.gateway.BillingModels.BillingEventType;
import com.cmo2.integrations.domain.WebhookEvent;
import com.cmo2.integrations.repository.WebhookEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Receives Stripe webhooks: verifies the signature via the active gateway, persists the raw
 * event to {@code webhook_events} (source = STRIPE) for replay/audit, then drives the
 * subscription lifecycle. Mirrors the GoHighLevel webhook pattern (NON-NEGOTIABLE #5).
 */
@Service
public class StripeWebhookService {

    private static final Logger log = LoggerFactory.getLogger(StripeWebhookService.class);

    private final Map<String, BillingGateway> gateways;
    private final String providerName;
    private final WebhookEventRepository webhookRepo;
    private final BillingLifecycleService lifecycle;

    public StripeWebhookService(List<BillingGateway> gatewayBeans,
                                @Value("${cmo2.billing.provider:stub}") String providerName,
                                WebhookEventRepository webhookRepo,
                                BillingLifecycleService lifecycle) {
        this.gateways = gatewayBeans.stream().collect(Collectors.toMap(BillingGateway::name, Function.identity()));
        this.providerName = providerName;
        this.webhookRepo = webhookRepo;
        this.lifecycle = lifecycle;
    }

    @Transactional
    public void receive(String rawBody, String signature) {
        WebhookEvent record = new WebhookEvent();
        record.setSource("STRIPE");
        record.setPayloadJson(rawBody);
        record.setCreatedAt(Instant.now());

        BillingEvent event = gateway().parseWebhook(rawBody == null ? "" : rawBody, signature);
        record.setEventType(event.rawType());
        record.setSignatureValid(event.signatureValid());

        if (!event.signatureValid()) {
            log.warn("Rejected Stripe webhook with invalid signature (type={})", event.rawType());
        } else if (event.type() != BillingEventType.IGNORED) {
            Long tenantId = lifecycle.apply(event);
            record.setTenantId(tenantId);
        }

        record.setProcessed(true);
        record.setProcessedAt(Instant.now());
        webhookRepo.save(record);
    }

    private BillingGateway gateway() {
        return gateways.getOrDefault(providerName, gateways.get("stub"));
    }
}
