package com.cmo2.integrations.service;

import com.cmo2.integrations.core.Platform;
import com.cmo2.integrations.domain.PlatformConnection;
import com.cmo2.integrations.domain.WebhookEvent;
import com.cmo2.integrations.gohighlevel.GhlSignatureVerifier;
import com.cmo2.integrations.repository.PlatformConnectionRepository;
import com.cmo2.integrations.repository.WebhookEventRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Receives inbound platform webhooks. Per NON-NEGOTIABLE RULE #5: every webhook is
 * persisted raw (replayable) and signature-verified before it is trusted. The tenant is
 * resolved from the payload's location (webhooks arrive unauthenticated).
 */
@Service
public class WebhookService {

    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);

    private final WebhookEventRepository webhookRepo;
    private final PlatformConnectionRepository connectionRepo;
    private final ObjectMapper objectMapper;
    private final GhlSignatureVerifier ghlSignatureVerifier;

    public WebhookService(WebhookEventRepository webhookRepo,
                          PlatformConnectionRepository connectionRepo,
                          ObjectMapper objectMapper,
                          GhlSignatureVerifier ghlSignatureVerifier) {
        this.webhookRepo = webhookRepo;
        this.connectionRepo = connectionRepo;
        this.objectMapper = objectMapper;
        this.ghlSignatureVerifier = ghlSignatureVerifier;
    }

    @Transactional
    public void receiveGoHighLevel(String rawBody, String signature) {
        WebhookEvent event = new WebhookEvent();
        event.setSource(Platform.GOHIGHLEVEL.name());
        event.setPayloadJson(rawBody);
        event.setCreatedAt(Instant.now());

        String locationId = null;
        try {
            JsonNode root = objectMapper.readTree(rawBody);
            event.setEventType(text(root, "type"));
            locationId = firstNonNull(text(root, "locationId"), text(root, "location_id"));
        } catch (Exception e) {
            log.warn("Unparseable GHL webhook body: {}", e.getMessage());
        }

        // Resolve tenant from the location (unauthenticated lookup).
        if (locationId != null) {
            PlatformConnection conn = connectionRepo
                    .findFirstByPlatformAndLocationId(Platform.GOHIGHLEVEL, locationId)
                    .orElse(null);
            if (conn != null) {
                event.setTenantId(conn.getTenantId());
            }
        }

        event.setSignatureValid(ghlSignatureVerifier.verify(rawBody, signature));
        event.setProcessed(true);          // Phase 3 persists + verifies; deeper processing is later
        event.setProcessedAt(Instant.now());
        webhookRepo.save(event);
    }

    private String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }
}
