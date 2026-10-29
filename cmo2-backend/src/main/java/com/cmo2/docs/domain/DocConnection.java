package com.cmo2.docs.domain;

import com.cmo2.shared.tenancy.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * The agency's connection to one workspace tool. The secret (API token / webhook URL) is stored
 * only as AES-256-GCM ciphertext and shown as {@code ••••last4}; {@code configJson} holds the
 * non-secret settings (page/space/team ids).
 */
@Entity
@Table(name = "tenant_doc_connections",
        uniqueConstraints = @UniqueConstraint(name = "uk_doc_conn_tenant_provider", columnNames = {"tenant_id", "provider"}))
@Getter
@Setter
public class DocConnection extends BaseTenantEntity {

    public static final String STATUS_CONNECTED = "CONNECTED";
    public static final String STATUS_ERROR = "ERROR";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private DocProvider provider;

    @Lob
    @Column(columnDefinition = "LONGTEXT", name = "secret_enc", nullable = false)
    private String secretEnc;

    @Column(name = "secret_last4", length = 8)
    private String secretLast4;

    @Lob
    @Column(columnDefinition = "LONGTEXT", name = "config_json")
    private String configJson;

    @Column(nullable = false, length = 16)
    private String status = STATUS_ERROR;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "last_checked_at")
    private Instant lastCheckedAt;
}
