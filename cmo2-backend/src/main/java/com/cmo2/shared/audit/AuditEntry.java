package com.cmo2.shared.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Immutable audit row. Not a {@code BaseTenantEntity} because some events
 * (e.g. registration) precede a bound tenant, so tenant_id is nullable here.
 */
@Entity
@Table(name = "audit_log")
@Getter
@Setter
public class AuditEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "actor_id")
    private Long actorId;

    @Column(nullable = false, length = 80)
    private String action;

    @Column(length = 80)
    private String entity;

    @Column(name = "entity_id", length = 64)
    private String entityId;

    @Column(length = 1000)
    private String detail;

    @Column(length = 64)
    private String ip;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt = Instant.now();
}
