package com.cmo2.assets.service;

import com.cmo2.assets.domain.Asset;
import com.cmo2.assets.domain.AssetDeployment;
import com.cmo2.assets.domain.AssetStatus;
import com.cmo2.assets.domain.AssetType;
import com.cmo2.assets.domain.DeploymentStatus;
import com.cmo2.assets.dto.AssetDtos.DeployRequest;
import com.cmo2.assets.dto.AssetDtos.DeploymentResponse;
import com.cmo2.assets.dto.AssetDtos.SocialAccountResponse;
import com.cmo2.assets.dto.AssetDtos.SocialAccountsResponse;
import com.cmo2.assets.events.AssetPublishedEvent;
import com.cmo2.assets.repository.AssetDeploymentRepository;
import com.cmo2.assets.repository.AssetRepository;
import com.cmo2.integrations.core.Platform;
import com.cmo2.integrations.core.PlatformCredentials;
import com.cmo2.integrations.core.SyncModels;
import com.cmo2.integrations.core.SyncModels.DeployResult;
import com.cmo2.integrations.domain.PlatformConnection;
import com.cmo2.integrations.gohighlevel.GhlApiException;
import com.cmo2.integrations.gohighlevel.GhlPostUnconfirmedException;
import com.cmo2.integrations.gohighlevel.GhlSocialPlanner;
import com.cmo2.integrations.gohighlevel.GhlSocialPlanner.ScheduledPost;
import com.cmo2.integrations.gohighlevel.GoHighLevelConnector;
import com.cmo2.integrations.repository.PlatformConnectionRepository;
import com.cmo2.integrations.service.ConnectionService;
import com.cmo2.integrations.service.ConnectorRegistry;
import com.cmo2.shared.audit.AuditService;
import com.cmo2.shared.exceptions.ApiException;
import com.cmo2.shared.notify.TeamNotifier;
import com.cmo2.shared.security.TenantContext;
import com.cmo2.shared.tenancy.TenantGuard;
import com.cmo2.tenancy.repository.CompanyRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Publishes an approved asset through the {@link ConnectorRegistry} and records every attempt.
 * Nothing that hasn't been approved can leave CMO².
 *
 * <p>A live GoHighLevel social post is scheduled in the customer's Social Planner via
 * {@link GhlSocialPlanner} (and so through the capability guard). POSTs are never retried
 * automatically: if GHL doesn't answer, the attempt is UNCONFIRMED and a person must check the
 * Social Planner and confirm before trying again, so a post can't go out twice by accident.
 */
@Service
public class Deployer {

    static final Duration DEFAULT_DELAY = Duration.ofMinutes(5);
    static final Duration MIN_LEAD = Duration.ofMinutes(1);
    static final Duration MAX_AHEAD = Duration.ofDays(365);

    private final AssetRepository assetRepository;
    private final AssetDeploymentRepository deploymentRepository;
    private final ConnectorRegistry connectors;
    private final AuditService audit;
    private final PlatformConnectionRepository connectionRepo;
    private final ConnectionService connectionService;
    private final GoHighLevelConnector ghl;
    private final GhlSocialPlanner socialPlanner;
    private final CompanyRepository companies;
    private final TeamNotifier team;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher events;

    public Deployer(AssetRepository assetRepository, AssetDeploymentRepository deploymentRepository,
                    ConnectorRegistry connectors, AuditService audit,
                    PlatformConnectionRepository connectionRepo, ConnectionService connectionService,
                    GoHighLevelConnector ghl, GhlSocialPlanner socialPlanner, CompanyRepository companies,
                    TeamNotifier team, ObjectMapper objectMapper, ApplicationEventPublisher events) {
        this.assetRepository = assetRepository;
        this.deploymentRepository = deploymentRepository;
        this.connectors = connectors;
        this.audit = audit;
        this.connectionRepo = connectionRepo;
        this.connectionService = connectionService;
        this.ghl = ghl;
        this.socialPlanner = socialPlanner;
        this.companies = companies;
        this.team = team;
        this.objectMapper = objectMapper;
        this.events = events;
    }

    @Transactional(noRollbackFor = GhlApiException.class)
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    public DeploymentResponse deploy(Long assetId, DeployRequest request) {
        Asset asset = TenantGuard.owned(assetRepository.findById(assetId))
                .orElseThrow(() -> ApiException.notFound("Asset not found"));
        if (asset.getStatus() == AssetStatus.DEPLOYED) {
            throw ApiException.conflict("This asset has already been published");
        }
        if (asset.getStatus() != AssetStatus.APPROVED) {
            throw ApiException.conflict("Approve this draft before publishing it (it's " + asset.getStatus() + ")");
        }
        Optional<AssetDeployment> last = deploymentRepository.findByAssetIdOrderByCreatedAtDesc(assetId).stream().findFirst();
        if (last.isPresent() && last.get().getStatus() == DeploymentStatus.UNCONFIRMED && !request.confirmRetry()) {
            throw ApiException.conflict("GoHighLevel never confirmed the last attempt, so the post may already exist. "
                    + "Check the customer's Social Planner; if it isn't there, publish again and confirm the retry.");
        }

        Platform platform = parsePlatform(request.platform());
        PlatformConnection conn = connectionRepo.findFirstByCompanyIdAndPlatform(asset.getCompanyId(), platform)
                .orElseThrow(() -> ApiException.badRequest(
                        platform.displayName + " is not connected for this company"));
        PlatformCredentials creds = connectionService.credentials(conn);

        AssetDeployment deployment = new AssetDeployment();
        deployment.setAssetId(assetId);
        deployment.setPlatform(platform);
        if (platform == Platform.GOHIGHLEVEL && asset.getType() == AssetType.SOCIAL_MEDIA && ghl.isLive(creds)) {
            scheduleSocialPost(asset, request, deployment);
        } else {
            DeployResult result = connectors.require(platform).deployAsset(new SyncModels.DeployRequest(
                    asset.getCompanyId(), asset.getType().name(), asset.getContentJson(), creds));
            if (result.accepted()) {
                deployment.setStatus(DeploymentStatus.ACCEPTED);
                deployment.setExternalRef(result.externalRef());
                deployment.setDeployedAt(Instant.now());
            } else {
                deployment.setStatus(DeploymentStatus.FAILED);
                deployment.setErrorMessage(clip(result.message()));
            }
        }

        if (deployment.getStatus() == DeploymentStatus.ACCEPTED) {
            asset.setStatus(AssetStatus.DEPLOYED);
            assetRepository.save(asset);
            events.publishEvent(new AssetPublishedEvent(asset.getTenantId(), asset.getCompanyId(), assetId,
                    deployment.getScheduledFor() == null ? Instant.now() : deployment.getScheduledFor()));
        }
        deployment = deploymentRepository.save(deployment);

        audit.record(deployment.getStatus() == DeploymentStatus.ACCEPTED ? "ASSET_PUBLISHED" : "ASSET_PUBLISH_FAILED",
                "Asset", String.valueOf(assetId), platform.displayName + " → " + deployment.getStatus()
                        + (deployment.getScheduledFor() == null ? "" : " for " + deployment.getScheduledFor()));
        if (deployment.getStatus() == DeploymentStatus.UNCONFIRMED || deployment.getStatus() == DeploymentStatus.FAILED) {
            notifyFailure(asset, deployment);
        }
        return DeploymentResponse.of(deployment);
    }

    private void scheduleSocialPost(Asset asset, DeployRequest request, AssetDeployment deployment) {
        Instant now = Instant.now();
        Instant at = request.scheduleAt() == null ? now.plus(DEFAULT_DELAY) : request.scheduleAt();
        if (at.isBefore(now.plus(MIN_LEAD))) {
            throw ApiException.badRequest("Pick a time at least a minute from now");
        }
        if (at.isAfter(now.plus(MAX_AHEAD))) {
            throw ApiException.badRequest("Posts can be scheduled at most a year ahead");
        }
        String text = ApprovalService.body(asset, objectMapper);
        deployment.setScheduledFor(at);
        try {
            ScheduledPost post = socialPlanner.schedule(asset.getCompanyId(), text, request.accountIds(),
                    request.mediaUrls(), at, "asset:" + asset.getId());
            deployment.setStatus(DeploymentStatus.ACCEPTED);
            deployment.setExternalRef(post.postId());
            deployment.setDeployedAt(Instant.now());
            deployment.setTargetAccounts(clip(String.join(",", post.accountIds()), 2000));
        } catch (GhlPostUnconfirmedException e) {
            deployment.setStatus(DeploymentStatus.UNCONFIRMED);
            deployment.setErrorMessage(clip("GoHighLevel didn't confirm the post (" + e.getMessage()
                    + "). It may still have been created: check the Social Planner before publishing again."));
        } catch (GhlApiException e) {
            deployment.setStatus(DeploymentStatus.FAILED);
            deployment.setErrorMessage(clip("GoHighLevel refused the post: " + e.getMessage()));
        }
    }

    /** The customer's social accounts as connected in GHL, for choosing where a post goes. */
    @Transactional(readOnly = true, noRollbackFor = GhlApiException.class)
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    public SocialAccountsResponse socialAccounts(Long companyId) {
        TenantGuard.owned(companies.findById(companyId)).orElseThrow(() -> ApiException.notFound("Company not found"));
        Optional<PlatformConnection> conn = connectionRepo.findFirstByCompanyIdAndPlatform(companyId, Platform.GOHIGHLEVEL);
        if (conn.isEmpty()) {
            return new SocialAccountsResponse(false, List.of(), "This customer has no GoHighLevel connection");
        }
        if (!ghl.isLive(connectionService.credentials(conn.get()))) {
            return new SocialAccountsResponse(false, List.of(), "Demo connection: publishing is simulated");
        }
        List<SocialAccountResponse> accounts = socialPlanner.accounts(companyId).stream()
                .map(a -> new SocialAccountResponse(a.id(), a.name(), a.platform(), a.expired()))
                .toList();
        return new SocialAccountsResponse(true, accounts,
                accounts.isEmpty() ? "No social accounts are connected in this customer's GoHighLevel" : null);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','STRATEGIST','ANALYST','VIEWER')")
    public List<DeploymentResponse> listForAsset(Long assetId) {
        TenantGuard.owned(assetRepository.findById(assetId))
                .orElseThrow(() -> ApiException.notFound("Asset not found"));
        return deploymentRepository.findByAssetIdOrderByCreatedAtDesc(assetId).stream()
                .map(DeploymentResponse::of)
                .toList();
    }

    private void notifyFailure(Asset asset, AssetDeployment d) {
        String company = companies.findById(asset.getCompanyId()).map(c -> c.getName()).orElse("a customer");
        team.notify(TenantContext.requireTenantId(), (d.getStatus() == DeploymentStatus.UNCONFIRMED
                ? ":warning: Unconfirmed publish for " : ":x: Publish failed for ") + company + " ("
                + asset.getType().displayName + " #" + asset.getId() + "): " + d.getErrorMessage());
    }

    private Platform parsePlatform(String raw) {
        try {
            return Platform.valueOf(raw.trim().toUpperCase());
        } catch (Exception e) {
            throw ApiException.badRequest("Unknown platform: " + raw);
        }
    }

    private static String clip(String s) {
        return clip(s, 1000);
    }

    private static String clip(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
