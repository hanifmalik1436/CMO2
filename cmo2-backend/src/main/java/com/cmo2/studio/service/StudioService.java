package com.cmo2.studio.service;

import com.cmo2.assets.domain.ContentFormat;
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
import com.cmo2.studio.dto.StudioDtos.LinkSourceRequest;
import com.cmo2.studio.dto.StudioDtos.SourceDetail;
import com.cmo2.studio.dto.StudioDtos.SourceView;
import com.cmo2.studio.repository.ContentBatchRepository;
import com.cmo2.studio.repository.ContentIdeaRepository;
import com.cmo2.studio.repository.ContentSourceRepository;
import com.cmo2.studio.service.ContentBatchRunner.Plan;
import com.cmo2.studio.service.SourceIntake.Extracted;
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
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The Content Studio (Strange Loop stage 1, Innovation): sources → ideas → batches of drafts for
 * chosen platforms and post types. Sources come in as pasted text, uploaded files, web links or
 * voice recordings, and each is analysed for ideas in the background. Ideas come from the strategy
 * model; each draft from that format's agent (the generation model). Every draft lands in Approvals.
 */
@Service
public class StudioService {

    private final ContentSourceRepository sources;
    private final ContentIdeaRepository ideas;
    private final ContentBatchRepository batches;
    private final CompanyRepository companies;
    private final IdeaFinder finder;
    private final SourceIntake intake;
    private final SourceAnalysisRunner analysis;
    private final AuditService audit;
    private final ContentBatchRunner runner;
    private final ObjectMapper json;
    private final int maxPiecesPerBatch;

    public StudioService(ContentSourceRepository sources, ContentIdeaRepository ideas, ContentBatchRepository batches,
                         CompanyRepository companies, IdeaFinder finder, SourceIntake intake,
                         SourceAnalysisRunner analysis, AuditService audit, ContentBatchRunner runner,
                         ObjectMapper json, @Value("${cmo2.studio.max-pieces-per-batch:40}") int maxPiecesPerBatch) {
        this.sources = sources;
        this.ideas = ideas;
        this.batches = batches;
        this.companies = companies;
        this.finder = finder;
        this.intake = intake;
        this.analysis = analysis;
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
        Map<Long, Long> ideaCounts = byId(ideas.countsBySource(company.getTenantId(), companyId));
        Map<Long, Long> generated = byId(batches.generatedBySource(company.getTenantId(), companyId));
        return sources.findByTenantIdAndCompanyIdOrderByCreatedAtDesc(company.getTenantId(), companyId).stream()
                .map(s -> view(s, ideaCounts.getOrDefault(s.getId(), 0L), generated.getOrDefault(s.getId(), 0L)))
                .toList();
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','STRATEGIST','ANALYST','VIEWER')")
    public SourceDetail getSource(Long sourceId) {
        return detail(ownedSource(sourceId));
    }

    /** Pasted text or a blank canvas; its ideas are found in the background. */
    @Transactional
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','STRATEGIST')")
    public SourceDetail createSource(Long companyId, CreateSourceRequest req) {
        owned(companyId);
        return save(companyId, new Extracted(req.title().strip(), req.kind(), req.body().strip(), null, null, null));
    }

    /** A .txt, .md, .pdf or .docx file, or an audio file (transcribed; the audio isn't kept). */
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','STRATEGIST')")
    public SourceDetail uploadSource(Long companyId, String filename, String mimeType, byte[] bytes, String title) {
        owned(companyId);
        return save(companyId, intake.fromFile(companyId, filename, mimeType, bytes, title));
    }

    /** A voice recording made in the studio: transcribed, then the audio is dropped. */
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','STRATEGIST')")
    public SourceDetail recordSource(Long companyId, String filename, String mimeType, byte[] bytes, String title) {
        owned(companyId);
        return save(companyId, intake.fromRecording(companyId, filename, mimeType, bytes, title));
    }

    /** The readable text of a web page. */
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','STRATEGIST')")
    public SourceDetail linkSource(Long companyId, LinkSourceRequest req) {
        owned(companyId);
        return save(companyId, intake.fromLink(req.url(), req.title()));
    }

    @Transactional
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','STRATEGIST')")
    public SourceView star(Long sourceId, boolean starred) {
        ContentSource s = ownedSource(sourceId);
        s.setStarred(starred);
        s = sources.save(s);
        return view(s, ideas.countByTenantIdAndSourceId(s.getTenantId(), sourceId),
                batches.generatedForSource(s.getTenantId(), sourceId));
    }

    @Transactional
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    public void deleteSource(Long sourceId) {
        ContentSource s = ownedSource(sourceId);
        ideas.deleteByTenantIdAndSourceId(s.getTenantId(), sourceId);
        sources.delete(s);
        audit.record("CONTENT_SOURCE_DELETED", "ContentSource", String.valueOf(sourceId), s.getTitle());
    }

    private SourceDetail save(Long companyId, Extracted in) {
        ContentSource s = new ContentSource();
        s.setCompanyId(companyId);
        s.setTitle(in.title());
        s.setKind(in.kind());
        s.setBody(in.text());
        s.setOriginUrl(in.originUrl());
        s.setOriginFile(in.originFile());
        s.setStatus(ContentSource.PENDING);
        s.setStatusNote(in.note());
        s = sources.save(s);
        audit.record("CONTENT_SOURCE_ADDED", "ContentSource", String.valueOf(s.getId()),
                in.kind() + ": " + s.getTitle());
        Long tenantId = s.getTenantId();
        Long sourceId = s.getId();
        afterCommit(() -> analysis.submit(tenantId, sourceId));
        return new SourceDetail(view(s, 0, 0), s.getBody(), List.of());
    }

    // ---- ideas ---------------------------------------------------------------------------

    /** Re-analyze: asks the strategy model for the {@code count} strongest ideas now and replaces the earlier ones. */
    @Transactional
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','STRATEGIST')")
    public SourceDetail analyze(Long sourceId, int count) {
        ContentSource s = ownedSource(sourceId);
        if (ContentSource.PENDING.equals(s.getStatus()) || ContentSource.ANALYZING.equals(s.getStatus())) {
            throw ApiException.conflict("Still finding ideas in this source; try again when it's done");
        }
        JsonNode answer = finder.ask(s, count);
        finder.save(s.getTenantId(), sourceId, answer);
        return detail(ownedSource(sourceId));
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
        batch.setRequestJson(write(new Plan(ideaIds, req.includeFullSource(), formats, req.draftsPerFormat(),
                req.translate(), req.smartSchedule())));
        batch = batches.save(batch);
        source.setLastUsedAt(Instant.now());
        sources.save(source);
        audit.record("CONTENT_BATCH_STARTED", "ContentBatch", String.valueOf(batch.getId()),
                pieces + " pieces for company " + companyId);

        Long tenantId = TenantContext.requireTenantId();
        Long batchId = batch.getId();
        afterCommit(() -> runner.submit(tenantId, batchId));
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

    /** Runs {@code task} once the current transaction commits (or now, outside one). */
    private static void afterCommit(Runnable task) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }
            });
        } else {
            task.run();
        }
    }

    private SourceDetail detail(ContentSource s) {
        List<IdeaView> list = ideaViews(s);
        return new SourceDetail(view(s, list.size(), batches.generatedForSource(s.getTenantId(), s.getId())),
                s.getBody(), list);
    }

    private List<IdeaView> ideaViews(ContentSource s) {
        return ideas.findByTenantIdAndSourceIdOrderByPositionAsc(s.getTenantId(), s.getId()).stream()
                .map(i -> new IdeaView(i.getId(), i.getIdeaType(), i.getTitle(), i.getSummary(), hooks(i), i.getQuote(),
                        i.getViralityScore(), i.getViralityReason()))
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

    private static SourceView view(ContentSource s, long ideaCount, long generated) {
        String body = s.getBody() == null ? "" : s.getBody();
        String preview = body.replaceAll("\\s+", " ").strip();
        return new SourceView(s.getId(), s.getCompanyId(), s.getTitle(), s.getKind(), body.length(),
                preview.length() <= 240 ? preview : preview.substring(0, 240) + "…", s.getAnalyzedAt(), (int) ideaCount,
                s.getCreatedAt(), s.getStatus(), s.getStatusNote(), s.isStarred(), s.getLastUsedAt(), generated,
                s.getOriginUrl(), s.getOriginFile());
    }

    private static Map<Long, Long> byId(List<Object[]> rows) {
        Map<Long, Long> out = new HashMap<>();
        for (Object[] r : rows) out.put(((Number) r[0]).longValue(), ((Number) r[1]).longValue());
        return out;
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
