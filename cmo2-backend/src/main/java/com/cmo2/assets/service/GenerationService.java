package com.cmo2.assets.service;

import com.cmo2.ai.AiCallScope;
import com.cmo2.ai.AiMessages.AiResult;
import com.cmo2.ai.AiService;
import com.cmo2.ai.PromptRegistry;
import com.cmo2.assets.domain.Asset;
import com.cmo2.assets.domain.AssetStatus;
import com.cmo2.assets.domain.AssetType;
import com.cmo2.assets.domain.Language;
import com.cmo2.assets.dto.AssetDtos.AssetResponse;
import com.cmo2.assets.dto.AssetDtos.GenerateResponse;
import com.cmo2.assets.events.AssetGeneratedEvent;
import com.cmo2.assets.repository.AssetRepository;
import com.cmo2.customers.service.CustomerIdentityService;
import com.cmo2.shared.audit.AuditService;
import com.cmo2.shared.exceptions.ApiException;
import com.cmo2.shared.security.TenantContext;
import com.cmo2.shared.tenancy.TenantGuard;
import com.cmo2.tenancy.domain.Company;
import com.cmo2.tenancy.repository.CompanyRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * The Phase 4 pipeline: generate (EN) → translate (ES) → versioned, deployable asset.
 * Every call is budget-checked and metered through {@link AiService}; authorization is
 * enforced here; all reads are tenant-scoped.
 */
@Service
public class GenerationService {

    private final AiService aiService;
    private final PromptRegistry prompts;
    private final Translator translator;
    private final AssetRepository assetRepository;
    private final CompanyRepository companyRepository;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final CustomerIdentityService identity;

    public GenerationService(AiService aiService, PromptRegistry prompts, Translator translator,
                             AssetRepository assetRepository, CompanyRepository companyRepository,
                             AuditService audit, ApplicationEventPublisher events,
                             CustomerIdentityService identity) {
        this.aiService = aiService;
        this.prompts = prompts;
        this.translator = translator;
        this.assetRepository = assetRepository;
        this.companyRepository = companyRepository;
        this.audit = audit;
        this.events = events;
        this.identity = identity;
    }

    @Transactional
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','STRATEGIST')")
    public GenerateResponse generate(Long companyId, String typeName, String brief) {
        try (AiCallScope scope = AiCallScope.forCompany(companyId)) {
            return generateInScope(companyId, typeName, brief);
        }
    }

    /**
     * Internal: a Strange Loop cycle drafting content for its customer. There's no signed-in user on
     * loop threads, so no method security; the cycle's own AI scope (customer, iteration, stage) is
     * kept, and the draft waits for approval like any other.
     */
    @Transactional
    public GenerateResponse generateForCycle(Long companyId, String typeName, String brief) {
        return generateInScope(companyId, typeName, brief);
    }

    private GenerateResponse generateInScope(Long companyId, String typeName, String brief) {
        AssetType type = parseType(typeName);
        Company company = TenantGuard.owned(companyRepository.findById(companyId))
                .orElseThrow(() -> ApiException.notFound("Company not found"));

        AiResult enResult = aiService.generate(
                prompts.forGeneration(type.name(), type.displayName, company.getName(),
                        identity.identityBlock(company.getTenantId(), companyId), brief));

        String title = type.displayName + " — " + company.getName();
        Asset english = new Asset();
        english.setCompanyId(companyId);
        english.setType(type);
        english.setLanguage(Language.EN);
        english.setStatus(AssetStatus.PENDING_APPROVAL);
        english.setTitle(title);
        english.setVersion(1);
        english.setTokensUsed((int) Math.min(Integer.MAX_VALUE, enResult.totalTokens()));
        english.setContentJson(translator.toContentJson(title, Language.EN, enResult.text(), enResult.model()));
        english = assetRepository.save(english);

        Asset spanish = translator.translateToSpanish(english, enResult.text());

        events.publishEvent(new AssetGeneratedEvent(
                TenantContext.getTenantId(), companyId, english.getId(), type.name()));
        audit.record("ASSET_GENERATED", "Asset", String.valueOf(english.getId()),
                type.displayName + " (EN+ES) for company " + companyId);

        return new GenerateResponse(AssetResponse.of(english), AssetResponse.of(spanish));
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','STRATEGIST','ANALYST','VIEWER')")
    public List<AssetResponse> list(Long companyId) {
        return assetRepository.findByCompanyIdOrderByCreatedAtDesc(companyId).stream()
                .map(AssetResponse::of)
                .toList();
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','STRATEGIST','ANALYST','VIEWER')")
    public AssetResponse get(Long assetId) {
        return AssetResponse.of(TenantGuard.owned(assetRepository.findById(assetId))
                .orElseThrow(() -> ApiException.notFound("Asset not found")));
    }

    private AssetType parseType(String raw) {
        try {
            return AssetType.valueOf(raw.trim().toUpperCase());
        } catch (Exception e) {
            throw ApiException.badRequest("Unknown asset type: " + raw);
        }
    }
}
