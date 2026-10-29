package com.cmo2.assets.service;

import com.cmo2.assets.domain.Asset;
import com.cmo2.assets.domain.AssetDeployment;
import com.cmo2.assets.domain.AssetStatus;
import com.cmo2.assets.repository.AssetDeploymentRepository;
import com.cmo2.assets.repository.AssetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * What happened to a customer's drafts over a period, for the Strange Loop's cycle record: what was
 * produced, what the owner approved or rejected, what was published, and what is still waiting.
 * Internal (loop threads), so the tenant is named explicitly.
 */
@Service
public class AssetActivityService {

    private final AssetRepository assets;
    private final AssetDeploymentRepository deployments;

    public AssetActivityService(AssetRepository assets, AssetDeploymentRepository deployments) {
        this.assets = assets;
        this.deployments = deployments;
    }

    public record AssetSummary(Long id, String type, String language, String status, String title, int version,
                               Instant createdAt) {
        static AssetSummary of(Asset a) {
            return new AssetSummary(a.getId(), a.getType().name(), a.getLanguage().name(), a.getStatus().name(),
                    a.getTitle(), a.getVersion(), a.getCreatedAt());
        }
    }

    /** {@code decision}: APPROVED or REJECTED; {@code note}: the reviewer's note or rejection reason. */
    public record Decision(Long assetId, String type, String language, String decision, String note, Instant at) {}

    /** One publish attempt: ACCEPTED (with when it goes out), FAILED or UNCONFIRMED. */
    public record Publication(Long assetId, String type, String language, String status, Instant scheduledFor,
                              String externalRef, String error, Instant at) {}

    public record Activity(List<AssetSummary> produced, List<Decision> decisions, List<Publication> publications,
                           List<AssetSummary> awaitingReview, List<AssetSummary> awaitingPublish,
                           Map<Long, String> lastPublishProblem) {}

    @Transactional(readOnly = true)
    public Activity activity(Long tenantId, Long companyId, Instant from, Instant to) {
        List<AssetSummary> produced = assets
                .findByTenantIdAndCompanyIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAsc(tenantId, companyId, from, to)
                .stream().map(AssetSummary::of).toList();

        List<Decision> decisions = assets
                .findByTenantIdAndCompanyIdAndReviewedAtGreaterThanEqualAndReviewedAtLessThanOrderByReviewedAtAsc(tenantId, companyId, from, to)
                .stream()
                .map(a -> new Decision(a.getId(), a.getType().name(), a.getLanguage().name(),
                        a.getStatus() == AssetStatus.REJECTED ? "REJECTED" : "APPROVED", a.getReviewNote(), a.getReviewedAt()))
                .toList();

        List<Long> ids = assets.idsFor(tenantId, companyId);
        Map<Long, Asset> byId = ids.isEmpty() ? Map.of() : assets.findAllById(ids).stream()
                .collect(Collectors.toMap(Asset::getId, Function.identity()));
        List<Publication> publications = ids.isEmpty() ? List.of() : deployments
                .findByTenantIdAndAssetIdInAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAsc(tenantId, ids, from, to)
                .stream()
                .filter(d -> byId.containsKey(d.getAssetId()))
                .map(d -> publication(byId.get(d.getAssetId()), d))
                .toList();

        List<AssetSummary> awaitingReview = assets
                .findByTenantIdAndCompanyIdAndStatusOrderByCreatedAtAsc(tenantId, companyId, AssetStatus.PENDING_APPROVAL)
                .stream().map(AssetSummary::of).toList();
        List<Asset> approved = assets.findByTenantIdAndCompanyIdAndStatusOrderByCreatedAtAsc(tenantId, companyId, AssetStatus.APPROVED);
        Map<Long, String> problems = new LinkedHashMap<>();
        for (Asset a : approved) {
            deployments.findByAssetIdOrderByCreatedAtDesc(a.getId()).stream().findFirst()
                    .filter(d -> d.getErrorMessage() != null)
                    .ifPresent(d -> problems.put(a.getId(), d.getStatus() + ": " + d.getErrorMessage()));
        }
        return new Activity(produced, decisions, publications, awaitingReview,
                approved.stream().map(AssetSummary::of).toList(), problems);
    }

    /** Drafts of this customer still waiting for approval. */
    @Transactional(readOnly = true)
    public long awaitingReviewCount(Long tenantId, Long companyId) {
        return assets.countByTenantIdAndCompanyIdAndStatus(tenantId, companyId, AssetStatus.PENDING_APPROVAL);
    }

    private static Publication publication(Asset a, AssetDeployment d) {
        return new Publication(a.getId(), a.getType().name(), a.getLanguage().name(), d.getStatus().name(),
                d.getScheduledFor(), d.getExternalRef(), d.getErrorMessage(), d.getCreatedAt());
    }
}
