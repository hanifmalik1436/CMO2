package com.cmo2.studio.service;

import com.cmo2.ai.AiCallScope;
import com.cmo2.ai.PromptRegistry;
import com.cmo2.assets.domain.ContentFormat;
import com.cmo2.assets.service.GenerationService;
import com.cmo2.assets.service.GenerationService.StudioOrigin;
import com.cmo2.customers.service.CustomerIdentityService;
import com.cmo2.shared.exceptions.ApiException;
import com.cmo2.shared.notify.TeamNotifier;
import com.cmo2.shared.security.TenantContext;
import com.cmo2.studio.domain.ContentBatch;
import com.cmo2.studio.domain.ContentIdea;
import com.cmo2.studio.domain.ContentSource;
import com.cmo2.studio.repository.ContentBatchRepository;
import com.cmo2.studio.repository.ContentIdeaRepository;
import com.cmo2.studio.repository.ContentSourceRepository;
import com.cmo2.tenancy.repository.CompanyRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Writes a batch's drafts in the background, one piece at a time, each in its own transaction, so a
 * failure loses one piece rather than the batch. Stops early when the customer's or the agency's AI
 * budget runs out. Announces the finished batch once (Slack), not per draft.
 */
@Component
public class ContentBatchRunner {

    private static final Logger log = LoggerFactory.getLogger(ContentBatchRunner.class);

    private final ContentBatchRepository batches;
    private final ContentSourceRepository sources;
    private final ContentIdeaRepository ideas;
    private final GenerationService generation;
    private final PromptRegistry prompts;
    private final CustomerIdentityService identity;
    private final CompanyRepository companies;
    private final TeamNotifier team;
    private final ObjectMapper json;
    private final TaskExecutor executor;

    public ContentBatchRunner(ContentBatchRepository batches, ContentSourceRepository sources, ContentIdeaRepository ideas,
                              GenerationService generation, PromptRegistry prompts, CustomerIdentityService identity,
                              CompanyRepository companies, TeamNotifier team, ObjectMapper json,
                              @Qualifier("studioTaskExecutor") TaskExecutor executor) {
        this.batches = batches;
        this.sources = sources;
        this.ideas = ideas;
        this.generation = generation;
        this.prompts = prompts;
        this.identity = identity;
        this.companies = companies;
        this.team = team;
        this.json = json;
        this.executor = executor;
    }

    /** What a batch was asked for (stored as the batch's request JSON). */
    public record Plan(List<Long> ideaIds, boolean includeFullSource, List<String> formats, int draftsPerFormat,
                       boolean translate) {}

    /** One thing to write about: an idea, or the whole source ({@code ideaId} null). */
    record Material(Long ideaId, String name, String label, String text) {}

    public void submit(Long tenantId, Long batchId) {
        executor.execute(() -> run(tenantId, batchId));
    }

    void run(Long tenantId, Long batchId) {
        TenantContext.set(tenantId, null);
        ContentBatch batch = null;
        try {
            batch = batches.findById(batchId).filter(b -> tenantId.equals(b.getTenantId())).orElse(null);
            if (batch == null) return;
            ContentSource source = sources.findById(batch.getSourceId())
                    .filter(s -> tenantId.equals(s.getTenantId())).orElse(null);
            if (source == null) {
                finish(batch, ContentBatch.FAILED, "The source was deleted before the batch ran.");
                return;
            }
            Plan plan = json.readValue(batch.getRequestJson(), Plan.class);
            String identityBlock = identity.identityBlock(tenantId, batch.getCompanyId());
            List<Material> materials = materials(tenantId, source, plan);

            String stopReason = null;
            try (AiCallScope scope = AiCallScope.forCompany(batch.getCompanyId())) {
                outer:
                for (Material m : materials) {
                    for (String formatKey : plan.formats()) {
                        ContentFormat format = ContentFormat.of(formatKey);
                        for (int d = 1; d <= plan.draftsPerFormat(); d++) {
                            try {
                                generation.draftFromPrompt(batch.getCompanyId(), format.assetType,
                                        format.platformLabel + " " + format.label.toLowerCase() + " — " + m.name(),
                                        prompts.forContentPiece(format.name(), format.platformLabel, format.label,
                                                format.guidance, format.maxChars, identityBlock, m.label(), m.text(),
                                                d, plan.draftsPerFormat()),
                                        new StudioOrigin(format.platform, format.name(), batchId, m.ideaId()),
                                        plan.translate());
                                batch.setCompleted(batch.getCompleted() + 1);
                            } catch (ApiException e) {
                                batch.setFailed(batch.getFailed() + 1);
                                if (e.getStatus() == HttpStatus.TOO_MANY_REQUESTS) {
                                    stopReason = e.getMessage();
                                    batch = batches.save(batch);
                                    break outer;
                                }
                                log.warn("Batch {}: a {} draft failed: {}", batchId, formatKey, e.getMessage());
                            } catch (RuntimeException e) {
                                batch.setFailed(batch.getFailed() + 1);
                                log.warn("Batch {}: a {} draft failed: {}", batchId, formatKey, e.toString());
                            }
                            batch = batches.save(batch);
                        }
                    }
                }
            }
            String status = batch.getCompleted() == batch.getTotalPieces() ? ContentBatch.COMPLETED
                    : batch.getCompleted() == 0 ? ContentBatch.FAILED : ContentBatch.PARTIAL;
            finish(batch, status, stopReason == null ? null : "Stopped early: " + stopReason);
            announce(tenantId, batch);
        } catch (Exception e) {
            log.error("Content batch {} failed", batchId, e);
            if (batch != null) finish(batch, ContentBatch.FAILED, "Batch failed: " + e.getMessage());
        } finally {
            TenantContext.clear();
        }
    }

    private List<Material> materials(Long tenantId, ContentSource source, Plan plan) {
        Map<Long, ContentIdea> byId = ideas.findByTenantIdAndSourceIdOrderByPositionAsc(tenantId, source.getId()).stream()
                .collect(Collectors.toMap(ContentIdea::getId, Function.identity()));
        List<Material> out = new ArrayList<>();
        for (Long id : plan.ideaIds()) {
            ContentIdea i = byId.get(id);
            if (i == null) continue; // re-analysed since the batch was requested
            StringBuilder t = new StringBuilder("Title: ").append(i.getTitle()).append("\nType: ").append(i.getIdeaType());
            if (i.getSummary() != null) t.append("\nSummary: ").append(i.getSummary());
            List<String> hooks = hooks(i);
            if (!hooks.isEmpty()) t.append("\nHooks:\n- ").append(String.join("\n- ", hooks));
            if (i.getQuote() != null) t.append("\nSupporting quote: \"").append(i.getQuote()).append('"');
            out.add(new Material(i.getId(), clip(i.getTitle(), 120), "the idea to write about", t.toString()));
        }
        if (plan.includeFullSource()) {
            out.add(new Material(null, clip(source.getTitle(), 120), "the full source material",
                    "Source: " + source.getTitle() + "\n" + source.getBody()));
        }
        return out;
    }

    private List<String> hooks(ContentIdea i) {
        try {
            if (i.getHooksJson() != null) return json.readValue(i.getHooksJson(), new TypeReference<>() {});
        } catch (Exception ignored) {
            // written without hooks
        }
        return List.of();
    }

    private void finish(ContentBatch batch, String status, String note) {
        batch.setStatus(status);
        batch.setNote(note == null || note.length() <= 1000 ? note : note.substring(0, 1000));
        batch.setFinishedAt(Instant.now());
        batches.save(batch);
    }

    private void announce(Long tenantId, ContentBatch batch) {
        if (batch.getCompleted() == 0) return;
        String company = companies.findById(batch.getCompanyId()).map(c -> c.getName()).orElse("a customer");
        team.notify(tenantId, ":sparkles: Content Studio wrote " + batch.getCompleted() + " draft"
                + (batch.getCompleted() == 1 ? "" : "s") + " for " + company
                + (batch.getFailed() > 0 ? " (" + batch.getFailed() + " failed)" : "") + ". Review them in Approvals.");
    }

    private static String clip(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
