package com.cmo2.assets.service;

import com.cmo2.assets.domain.Asset;
import com.cmo2.assets.domain.AssetStatus;
import com.cmo2.assets.dto.AssetDtos.ApprovalItem;
import com.cmo2.assets.dto.AssetDtos.AssetResponse;
import com.cmo2.assets.dto.AssetDtos.DeploymentResponse;
import com.cmo2.assets.events.AssetReviewedEvent;
import com.cmo2.assets.repository.AssetDeploymentRepository;
import com.cmo2.assets.repository.AssetRepository;
import com.cmo2.shared.audit.AuditService;
import com.cmo2.shared.exceptions.ApiException;
import com.cmo2.shared.security.TenantContext;
import com.cmo2.shared.tenancy.TenantGuard;
import com.cmo2.tenancy.domain.Company;
import com.cmo2.tenancy.repository.CompanyRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The review gate for everything CMO² writes for a customer (Strange Loop stage 1 output): drafts
 * wait as PENDING_APPROVAL until the owner or an admin edits, approves or rejects them. Only an
 * approved asset can be published ({@link Deployer}).
 */
@Service
public class ApprovalService {

    private final AssetRepository assets;
    private final AssetDeploymentRepository deployments;
    private final CompanyRepository companies;
    private final AuditService audit;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher events;

    public ApprovalService(AssetRepository assets, AssetDeploymentRepository deployments,
                           CompanyRepository companies, AuditService audit, ObjectMapper objectMapper,
                           ApplicationEventPublisher events) {
        this.assets = assets;
        this.deployments = deployments;
        this.companies = companies;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.events = events;
    }

    /** Drafts in one state (default: waiting for approval), across customers or for one, oldest first. */
    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    public List<ApprovalItem> queue(Long companyId, String status) {
        Long tenantId = TenantContext.requireTenantId();
        AssetStatus wanted = parseStatus(status);
        List<Asset> rows;
        if (companyId == null) {
            rows = assets.findByTenantIdAndStatusOrderByCreatedAtAsc(tenantId, wanted);
        } else {
            TenantGuard.owned(companies.findById(companyId))
                    .orElseThrow(() -> ApiException.notFound("Company not found"));
            rows = assets.findByTenantIdAndCompanyIdAndStatusOrderByCreatedAtAsc(tenantId, companyId, wanted);
        }
        Map<Long, String> names = companies.findAllById(rows.stream().map(Asset::getCompanyId).distinct().toList())
                .stream()
                .filter(c -> tenantId.equals(c.getTenantId()))
                .collect(Collectors.toMap(Company::getId, Company::getName, (a, b) -> a));
        return rows.stream().map(a -> new ApprovalItem(AssetResponse.of(a), names.get(a.getCompanyId()),
                        deployments.findByAssetIdOrderByCreatedAtDesc(a.getId()).stream().findFirst()
                                .map(DeploymentResponse::of).orElse(null)))
                .toList();
    }

    /** Replaces a draft's text before it is approved; the version goes up so the edit is visible. */
    @Transactional
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    public AssetResponse editContent(Long assetId, String body) {
        Asset asset = pending(assetId);
        ObjectNode content = content(asset);
        content.put("body", body.strip());
        content.put("editedAt", Instant.now().toString());
        asset.setContentJson(content.toString());
        asset.setVersion(asset.getVersion() + 1);
        asset = assets.save(asset);
        audit.record("ASSET_EDITED", "Asset", String.valueOf(assetId), "v" + asset.getVersion());
        return AssetResponse.of(asset);
    }

    @Transactional
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    public AssetResponse approve(Long assetId, String note) {
        Asset asset = review(pending(assetId), AssetStatus.APPROVED, note);
        events.publishEvent(new AssetReviewedEvent(asset.getTenantId(), asset.getCompanyId(), assetId, true, asset.getReviewNote()));
        audit.record("ASSET_APPROVED", "Asset", String.valueOf(assetId),
                asset.getType().displayName + " " + asset.getLanguage() + " v" + asset.getVersion());
        return AssetResponse.of(asset);
    }

    @Transactional
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    public AssetResponse reject(Long assetId, String reason) {
        Asset asset = review(pending(assetId), AssetStatus.REJECTED, reason);
        events.publishEvent(new AssetReviewedEvent(asset.getTenantId(), asset.getCompanyId(), assetId, false, asset.getReviewNote()));
        audit.record("ASSET_REJECTED", "Asset", String.valueOf(assetId), asset.getReviewNote());
        return AssetResponse.of(asset);
    }

    private Asset review(Asset asset, AssetStatus outcome, String note) {
        asset.setStatus(outcome);
        asset.setReviewedBy(TenantContext.getUserId());
        asset.setReviewedAt(Instant.now());
        asset.setReviewNote(note == null || note.isBlank() ? null : note.strip());
        return assets.save(asset);
    }

    private Asset pending(Long assetId) {
        Asset asset = TenantGuard.owned(assets.findById(assetId))
                .orElseThrow(() -> ApiException.notFound("Asset not found"));
        if (asset.getStatus() != AssetStatus.PENDING_APPROVAL) {
            throw ApiException.conflict("This draft isn't waiting for approval (it's " + asset.getStatus() + ")");
        }
        return asset;
    }

    private ObjectNode content(Asset asset) {
        try {
            if (asset.getContentJson() != null && objectMapper.readTree(asset.getContentJson()) instanceof ObjectNode o) {
                return o;
            }
        } catch (Exception ignored) {
            // Not JSON: start a fresh content object below.
        }
        return objectMapper.createObjectNode().put("title", asset.getTitle()).put("language", asset.getLanguage().name());
    }

    private static AssetStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) return AssetStatus.PENDING_APPROVAL;
        try {
            return AssetStatus.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Unknown status: " + raw);
        }
    }

    /** The readable text of an asset's content JSON (its "body"), or the raw content. */
    static String body(Asset asset, ObjectMapper objectMapper) {
        if (asset.getContentJson() == null) return "";
        try {
            var node = objectMapper.readTree(asset.getContentJson());
            if (node.hasNonNull("body")) return node.get("body").asText();
        } catch (Exception ignored) {
            // fall through to the raw content
        }
        return asset.getContentJson();
    }
}
