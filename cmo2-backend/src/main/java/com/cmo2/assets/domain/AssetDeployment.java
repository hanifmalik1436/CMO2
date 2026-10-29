package com.cmo2.assets.domain;

import com.cmo2.integrations.core.Platform;
import com.cmo2.shared.tenancy.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** Record of pushing an asset to an external platform. */
@Entity
@Table(name = "asset_deployments")
@Getter
@Setter
public class AssetDeployment extends BaseTenantEntity {

    @Column(name = "asset_id", nullable = false)
    private Long assetId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Platform platform;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private DeploymentStatus status = DeploymentStatus.PENDING;

    @Column(name = "external_ref", length = 255)
    private String externalRef;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "deployed_at")
    private Instant deployedAt;

    /** When the platform will publish it (social posts are always scheduled). */
    @Column(name = "scheduled_for")
    private Instant scheduledFor;

    /** Comma-separated social account ids the post targets. */
    @Column(name = "target_accounts", length = 2000)
    private String targetAccounts;
}
