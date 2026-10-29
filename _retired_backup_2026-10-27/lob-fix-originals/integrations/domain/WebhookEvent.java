package com.cmo2.integrations.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Raw inbound webhook, persisted before processing so it is replayable. Not a
 * {@link com.cmo2.shared.tenancy.BaseTenantEntity}: webhooks arrive with no auth
 * context and the tenant is resolved afterwards (from the payload's location), so
 * tenant_id is nullable here — same shape as the audit log.
 */
@Entity
@Table(name = "webhook_events")
@Getter
@Setter
public class WebhookEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(nullable = false, length = 32)
    private String source;

    @Column(name = "event_type", length = 80)
    private String eventType;

    @Lob
    @Column(name = "payload_json")
    private String payloadJson;

    @Column(name = "signature_valid", nullable = false)
    private boolean signatureValid = false;

    @Column(nullable = false)
    private boolean processed = false;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt = Instant.now();
}
