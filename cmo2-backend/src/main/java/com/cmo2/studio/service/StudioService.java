package com.cmo2.studio.service;

import com.cmo2.ai.AiCallScope;
import com.cmo2.ai.AiService;
import com.cmo2.ai.JsonSchemas;
import com.cmo2.ai.PromptRegistry;
import com.cmo2.assets.domain.ContentFormat;
import com.cmo2.customers.service.CustomerIdentityService;
import com.cmo2.shared.audit.AuditService;
import com.cmo2.shared.exceptions.ApiException;
import com.cmo2.shared.security.TenantContext;
import com.cmo2.shared.tenancy.TenantGuard;
import com.cmo2.studio.domain.ContentBatch;
import com.cmo2.studio.domain.ContentIdea;
import com.cmo2.studio.domain.ContentSource;
import com.cmo2.studio.dto.StudioDtos.BatchRequest;
import com.cmo2.studio.dto.StudioDtos.BatchView;
import com.cmo2.studio.dto.StudioDtos.CreateSourceRequest;
import com.cmo2.studio.dto.StudioDtos.FormatView;
import com.cmo2.studio.dto.StudioDtos.IdeaView;
import com.cmo2.studio.dto.StudioDtos.SourceDetail;
import com.cmo2.studio.dto.StudioDtos.SourceView;
import com.cmo2.studio.repository.ContentBatchRepository;
import com.cmo2.studio.repository.ContentIdeaRepository;
import com.cmo2.studio.repository.ContentSourceRepository;
import com.cmo2.studio.service.ContentBatchRunner.Plan;
import com.cmo2.tenancy.domain.Company;
import com.cmo2.tenancy.repository.CompanyRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The Content Studio (Strange Loop stage 1, Innovation): sources → ideas → batches of drafts for
 * chosen platforms and post types. Ideas come from the strategy model; each draft from that
 * format's agent (the generation model). Every draft lands in Approvals.
 */
@Service
public class StudioService {

    static final int MAX_IDEAS = 20;
    static final List<String> IDEA_TYPES = List.of("INSIGHT", "STORY", "TIP", "CONTRARIAN", "HOW_TO", "STAT");

    private final ContentSourceRepository sources;
    private final ContentIdeaRepository ideas;
    private final ContentBatchRepository batches;
    private final CompanyRepository companies;
    private final AiService ai;
    private final PromptRegistry prompts;
    private final CustomerIdentityService identity;
    private final AuditService audit;
    private final ContentBatchRunner runner;
    private final ObjectMapper json;
    private final int maxPiecesPerBatch;

    public StudioService(ContentSourceRepository sources, ContentIdeaRepository ideas, ContentBatchRepository batches,
                         CompanyRepository companies, AiService ai, PromptRegistry prompts,
                         CustomerIdentityService identity, AuditService audit, ContentBatchRunner runner,
                         ObjectMapper json, @Value("${cmo2.studio.max-pieces-per-batch:40}") int maxPiecesPerBatch) {
        this.sources = sources;
        this.ideas = ideas;
        this.batches = batches;
        this.companies = companies;
        this.ai = ai;
        this.prompts = prompts;
        this.identity = identity;
        this.audit = audit;
        this.runner = runner;
        this.json = json;
        this.maxPiecesPerBatch = maxPiecesPerBatch;
    }

    @PreAuthorize("hasAnyRole('OWNER','ADMIN','STRATEGIST','ANALYST','VIEWER')")
    public List<FormatView> formats() {
        return Arrays.stream(ContentFormat.values())
                .map(f -> new FormatView(f.name(), f.platform, f.platformLabel, f.label, f.assetType.name(),
                        f.publishable, f.ghlPlatform, f.maxChars, f.guidance))
                .toList();
    }

    // ---- sources -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','STRATEGIST','ANALYST','VIEWER')")
    public List<SourceView> listSources(Long companyId) {
        Company company = owned(companyId);
        return sources.findByTenantIdAndCompanyIdOrderByCreatedAtDesc(company.getTenantId(), companyId).stream()
                .map(this::view).toList();
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','STRATEGIST','ANALYST','VIEWER')")
    public SourceDetail getSource(Long sourceId) {
        ContentSource s = ownedSource(sourceId);
        return new SourceDetail(view(s), s.getBody(), ideaViews(s));
    }

    @Transactional
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','STRATEGIST')")
    public SourceDetail createSource(Long companyId, CreateSourceRequest req) {
        owned(companyId);
        ContentSource s = new ContentSource();
        s.setCompanyId(companyId);
        s.setTitle(req.title().strip());
        s.setKind(req.kind());
        s.setBody(req.body().strip());
        s = sources.save(s);
        audit.record("CONTENT_SOURCE_ADDED", "ContentSource", String.valueOf(s.getId()), s.getTitle());
        return new SourceDetail(view(s), s.getBody(), List.of());
    }

    @Transactional
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    public void deleteSource(Long sourceId) {
        ContentSource s = ownedSource(sourceId);
        ideas.deleteByTenantIdAndSourceId(s.getTenantId(), sourceId);
        sources.delete(s);
        audit.record("CONTENT_SOURCE_DELETED", "ContentSource", String.valueOf(sourceId), s.getTitle());
    }

    // ---- ideas ---------------------------------------------------------------------------

    /** Asks the strategy model for the {@code count} strongest ideas; replaces the source's earlier ideas. */
    @Transactional
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','STRATEGIST')")
    public SourceDetail analyze(Long sourceId, int count) {
        ContentSource s = ownedSource(sourceId);
        int n = Math.min(Math.max(count, 1), MAX_IDEAS);
        JsonNode answer;
        try (AiCallScope scope = AiCallScope.forCompany(s.getCompanyId())) {
            answer = ai.generateJson(prompts.forContentIdeas(identity.identityBlock(s.getTenantId(), s.getCompanyId()),
                    s.getTitle(), s.getBody(), n), ideasSchema(n));
        }
        ideas.deleteByTenantIdAndSourceId(s.getTenantId(), sourceId);
        int position = 0;
        for (JsonNode i : answer.path("ideas")) {
            ContentIdea idea = new ContentIdea();
            idea.setTenantId(s.getTenantId());
            idea.setCompanyId(s.getCompanyId());
            idea.setSourceId(sourceId);
            idea.setIdeaType(i.path("type").asText("INSIGHT"));
            idea.setTitle(i.path("title").asText());
            idea.setSummary(i.path("summary").asText(null));
            List<String> hooks = new ArrayList<>();
            i.path("hooks").forEach(h -> hooks.add(h.asText()));
            idea.setHooksJson(write(hooks));
            idea.setQuote(i.path("quote").asText(null));
            idea.setPosition(position++);
            ideas.save(idea);
        }
        s.setAnalyzedAt(Instant.now());
        sources.save(s);
        audit.record("CONTENT_IDEAS_EXTRACTED", "ContentSource", String.valueOf(sourceId), position + " ideas");
        return new SourceDetail(view(s), s.getBody(), ideaViews(s));
    }

    static Map<String, Object> ideasSchema(int count) {
        return JsonSchemas.object(Map.of("ideas", JsonSchemas.array(JsonSchemas.object(Map.of(
                "type", JsonSchemas.enumOf(IDEA_TYPES, "What kind of idea it is"),
                "title", JsonSchemas.string(200, "A headline-style title"),
                "summary", JsonSchemas.string(400, "One sentence on what it says"),
                "hooks", JsonSchemas.array(JsonSchemas.string(160, null), 3, "Short opening lines a post could use"),
                "quote", JsonSchemas.string(400, "The sentence from the material that supports it, quoted exactly"))),
                count, null)));
    }

    // ---- batches -------------------------------------------------------------------------

    /** Validates the request, records the batch and starts it in the background once saved. */
    @Transactional
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','STRATEGIST')")
    public BatchView startBatch(Long companyId, BatchRequest req) {
        owned(companyId);
        ContentSource source = ownedSource(req.sourceId());
        if (!source.getCompanyId().equals(companyId)) {
            throw ApiException.badRequest("That source belongs to another customer");
        }
        Set<Long> known = ideas.findByTenantIdAndSourceIdOrderByPositionAsc(source.getTenantId(), source.getId()).stream()
                .map(ContentIdea::getId).collect(Collectors.toSet());
        List<Long> ideaIds = req.ideaIds() == null ? List.of() : List.copyOf(new LinkedHashSet<>(req.ideaIds()));
        for (Long id : ideaIds) {
            if (!known.contains(id)) throw ApiException.badRequest("Idea " + id + " isn't one of this source's ideas");
        }
        List<String> formats = List.copyOf(new LinkedHashSet<>(req.formats()));
        for (String f : formats) {
            if (ContentFormat.of(f) == null) throw ApiException.badRequest("Unknown format: " + f);
        }
        int materials = ideaIds.size() + (req.includeFullSource() ? 1 : 0);
        if (materials == 0) {
            throw ApiException.badRequest("Choose at least one idea, or the full source");
        }
        int pieces = materials * formats.size() * req.draftsPerFormat();
        if (pieces > maxPiecesPerBatch) {
            throw ApiException.badRequest("That's " + pieces + " pieces; one batch can make at most " + maxPiecesPerBatch
                    + ". Choose fewer ideas, formats or drafts.");
        }

        ContentBatch batch = new ContentBatch();
        batch.setCompanyId(companyId);
        batch.setSourceId(source.getId());
        batch.setTotalPieces(pieces);
        batch.setRequestJson(write(new Plan(ideaIds, req.includeFullSource(), formats, req.draftsPerFormat(), req.translate())));
        batch = batches.save(batch);
        audit.record("CONTENT_BATCH_STARTED", "ContentBatch", String.valueOf(batch.getId()),
                pieces + " pieces for company " + companyId);

        Long tenantId = TenantContext.requireTenantId();
        Long batchId = batch.getId();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    runner.submit(tenantId, batchId);
                }
            });
        } else {
            runner.submit(tenantId, batchId);
        }
        return view(batch);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','STRATEGIST','ANALYST','VIEWER')")
    public BatchView batch(Long batchId) {
        return view(TenantGuard.owned(batches.findById(batchId))
                .orElseThrow(() -> ApiException.notFound("Batch not found")));
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','STRATEGIST','ANALYST','VIEWER')")
    public List<BatchView> batches(Long companyId, int limit) {
        Company company = owned(companyId);
        return batches.findByTenantIdAndCompanyIdOrderByCreatedAtDesc(company.getTenantId(), companyId,
                PageRequest.of(0, Math.min(Math.max(limit, 1), 50))).stream().map(StudioService::view).toList();
    }

    // ---- helpers -------------------------------------------------------------------------

    private List<IdeaView> ideaViews(ContentSource s) {
        return ideas.findByTenantIdAndSourceIdOrderByPositionAsc(s.getTenantId(), s.getId()).stream()
                .map(i -> new IdeaView(i.getId(), i.getIdeaType(), i.getTitle(), i.getSummary(), hooks(i), i.getQuote()))
                .toList();
    }

    List<String> hooks(ContentIdea i) {
        try {
            if (i.getHooksJson() != null) return json.readValue(i.getHooksJson(), new TypeReference<>() {});
        } catch (Exception ignored) {
            // shown without hooks
        }
        return List.of();
    }

    private SourceView view(ContentSource s) {
        String body = s.getBody() == null ? "" : s.getBody();
        String preview = body.replaceAll("\\s+", " ").strip();
        int ideaCount = ideas.findByTenantIdAndSourceIdOrderByPositionAsc(s.getTenantId(), s.getId()).size();
        return new SourceView(s.getId(), s.getCompanyId(), s.getTitle(), s.getKind(), body.length(),
                preview.length() <= 240 ? preview : preview.substring(0, 240) + "…", s.getAnalyzedAt(), ideaCount,
                s.getCreatedAt());
    }

    static BatchView view(ContentBatch b) {
        return new BatchView(b.getId(), b.getCompanyId(), b.getSourceId(), b.getStatus(), b.getTotalPieces(),
                b.getCompleted(), b.getFailed(), b.getNote(), b.getCreatedAt(), b.getFinishedAt());
    }

    private Company owned(Long companyId) {
        return TenantGuard.owned(companies.findById(companyId))
                .orElseThrow(() -> ApiException.notFound("Company not found"));
    }

    private ContentSource ownedSource(Long sourceId) {
        return TenantGuard.owned(sources.findById(sourceId))
                .orElseThrow(() -> ApiException.notFound("Source not found"));
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
