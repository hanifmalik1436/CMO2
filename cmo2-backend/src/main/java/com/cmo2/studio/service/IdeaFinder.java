package com.cmo2.studio.service;

import com.cmo2.ai.AiCallScope;
import com.cmo2.ai.AiService;
import com.cmo2.ai.JsonSchemas;
import com.cmo2.ai.PromptRegistry;
import com.cmo2.customers.service.CustomerIdentityService;
import com.cmo2.shared.audit.AuditService;
import com.cmo2.shared.security.TenantContext;
import com.cmo2.studio.domain.ContentIdea;
import com.cmo2.studio.domain.ContentSource;
import com.cmo2.studio.repository.ContentIdeaRepository;
import com.cmo2.studio.repository.ContentSourceRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Finds the ideas in a source: asks the strategy model (with the customer's identity and canon),
 * then replaces the source's ideas, best hook first. Used by the Re-analyze button and by the
 * background analysis every new source gets. Internal: callers check access first.
 */
@Component
public class IdeaFinder {

    static final int MAX_IDEAS = 20;
    static final List<String> IDEA_TYPES = List.of("INSIGHT", "STORY", "TIP", "CONTRARIAN", "HOW_TO", "STAT");

    private final ContentSourceRepository sources;
    private final ContentIdeaRepository ideas;
    private final AiService ai;
    private final PromptRegistry prompts;
    private final CustomerIdentityService identity;
    private final AuditService audit;
    private final ObjectMapper json;

    public IdeaFinder(ContentSourceRepository sources, ContentIdeaRepository ideas, AiService ai, PromptRegistry prompts,
                      CustomerIdentityService identity, AuditService audit, ObjectMapper json) {
        this.sources = sources;
        this.ideas = ideas;
        this.ai = ai;
        this.prompts = prompts;
        this.identity = identity;
        this.audit = audit;
        this.json = json;
    }

    /** Internal: the model's answer for up to {@code count} ideas. Makes the AI call; no database writes. */
    public JsonNode ask(ContentSource s, int count) {
        int n = Math.min(Math.max(count, 1), MAX_IDEAS);
        try (AiCallScope scope = AiCallScope.forCompany(s.getCompanyId())) {
            return ai.generateJson(prompts.forContentIdeas(identity.identityBlock(s.getTenantId(), s.getCompanyId()),
                    s.getTitle(), s.getBody(), n), schema(n));
        }
    }

    /** Internal: replaces the source's ideas with the answer's, highest score first, and marks it READY. */
    @Transactional
    public int save(Long tenantId, Long sourceId, JsonNode answer) {
        ContentSource s = sources.findById(sourceId).filter(x -> tenantId.equals(x.getTenantId())).orElse(null);
        if (s == null) return 0;
        List<JsonNode> found = new ArrayList<>();
        answer.path("ideas").forEach(found::add);
        found.sort(Comparator.comparingDouble((JsonNode i) -> i.path("viralityScore").asDouble(-1)).reversed());

        ideas.deleteByTenantIdAndSourceId(tenantId, sourceId);
        int position = 0;
        for (JsonNode i : found) {
            ContentIdea idea = new ContentIdea();
            idea.setTenantId(tenantId);
            idea.setCompanyId(s.getCompanyId());
            idea.setSourceId(sourceId);
            idea.setIdeaType(i.path("type").asText("INSIGHT"));
            idea.setTitle(i.path("title").asText());
            idea.setSummary(i.path("summary").asText(null));
            List<String> hooks = new ArrayList<>();
            i.path("hooks").forEach(h -> hooks.add(h.asText()));
            idea.setHooksJson(write(hooks));
            idea.setQuote(i.path("quote").asText(null));
            if (i.hasNonNull("viralityScore")) {
                idea.setViralityScore((int) Math.round(Math.min(100, Math.max(0, i.path("viralityScore").asDouble()))));
            }
            String reason = i.path("viralityReason").asText(null);
            idea.setViralityReason(reason == null || reason.length() <= 300 ? reason : reason.substring(0, 300));
            idea.setPosition(position++);
            ideas.save(idea);
        }
        s.setAnalyzedAt(Instant.now());
        s.setStatus(ContentSource.READY);
        s.setStatusNote(position == 0 ? "No ideas found in this source" : null);
        sources.save(s);
        audit.recordFor(tenantId, TenantContext.getUserId(), "CONTENT_IDEAS_EXTRACTED", "ContentSource", String.valueOf(sourceId),
                position + " ideas");
        return position;
    }

    /** Internal: where the background analysis stands. */
    @Transactional
    public void mark(Long tenantId, Long sourceId, String status, String note) {
        sources.findById(sourceId).filter(x -> tenantId.equals(x.getTenantId())).ifPresent(s -> {
            s.setStatus(status);
            s.setStatusNote(note == null || note.length() <= 500 ? note : note.substring(0, 500));
            sources.save(s);
        });
    }

    static Map<String, Object> schema(int count) {
        return JsonSchemas.object(Map.of("ideas", JsonSchemas.array(JsonSchemas.object(Map.of(
                "type", JsonSchemas.enumOf(IDEA_TYPES, "What kind of idea it is"),
                "title", JsonSchemas.string(200, "A headline-style title"),
                "summary", JsonSchemas.string(400, "One sentence on what it says"),
                "hooks", JsonSchemas.array(JsonSchemas.string(160, null), 3, "Short opening lines a post could use"),
                "quote", JsonSchemas.string(400, "The sentence from the material that supports it, quoted exactly"),
                "viralityScore", JsonSchemas.number(0, 100, "Hook strength for this audience, 0 to 100"),
                "viralityReason", JsonSchemas.string(200, "Why, naming the hook pattern it uses"))),
                count, null)));
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
