package com.cmo2.strangeloop.stages;

import com.cmo2.ai.AiService;
import com.cmo2.ai.PromptRegistry;
import com.cmo2.assets.dto.AssetDtos.GenerateResponse;
import com.cmo2.assets.service.AssetActivityService;
import com.cmo2.assets.service.GenerationService;
import com.cmo2.customers.service.CustomerIdentityService;
import com.cmo2.shared.exceptions.ApiException;
import com.cmo2.shared.security.TenantContext;
import com.cmo2.strangeloop.domain.StageName;
import com.cmo2.strangeloop.experiments.ExperimentService;
import com.cmo2.strangeloop.memory.CycleMemoryService;
import com.cmo2.strangeloop.memory.CycleMemoryService.Note;
import com.cmo2.strategists.service.StrategistDeliverableProvider;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Stage 1 — Innovation. Plans the customer's cycle with the strategy model (structured output) from
 * the customer's identity block, CMO²'s record of the last cycle, the owner's notes since, results of
 * earlier experiments and any approved strategist deliverables. The plan's best post ideas become drafts written by the
 * generation model; they wait in the approval queue like any other draft and are never published
 * from here. No new drafts are made while {@code cmo2.loop.max-pending-drafts} are already waiting.
 *
 * <p>If AI is unavailable (no key, budget reached, provider down) the cycle still runs on a fixed
 * plan and says why.
 */
@Service
public class Stage1Innovation implements LoopStage {

    private static final Logger log = LoggerFactory.getLogger(Stage1Innovation.class);
    static final String DRAFT_TYPE = "SOCIAL_MEDIA";

    private final StrategistDeliverableProvider strategistDeliverables;
    private final AiService ai;
    private final PromptRegistry prompts;
    private final CustomerIdentityService identity;
    private final GenerationService generation;
    private final AssetActivityService assets;
    private final ExperimentService experiments;
    private final int draftsPerCycle;
    private final int maxPendingDrafts;

    public Stage1Innovation(StrategistDeliverableProvider strategistDeliverables, AiService ai, PromptRegistry prompts,
                            CustomerIdentityService identity, GenerationService generation, AssetActivityService assets,
                            ExperimentService experiments,
                            @Value("${cmo2.loop.drafts-per-cycle:1}") int draftsPerCycle,
                            @Value("${cmo2.loop.max-pending-drafts:3}") int maxPendingDrafts) {
        this.strategistDeliverables = strategistDeliverables;
        this.ai = ai;
        this.prompts = prompts;
        this.identity = identity;
        this.generation = generation;
        this.assets = assets;
        this.experiments = experiments;
        this.draftsPerCycle = Math.max(0, draftsPerCycle);
        this.maxPendingDrafts = Math.max(0, maxPendingDrafts);
    }

    @Override
    public StageName name() {
        return StageName.INNOVATION;
    }

    @Override
    public Map<String, Object> run(StageContext context) {
        Long tenantId = TenantContext.requireTenantId();
        Long companyId = context.companyId();
        CycleMemoryService.Memory memory = context.get("memory") instanceof CycleMemoryService.Memory m ? m : null;
        Optional<String> approved = strategistDeliverables.latestApprovedDeliverables(companyId)
                .filter(s -> s != null && !s.isBlank());
        long waiting = assets.awaitingReviewCount(tenantId, companyId);

        Map<String, Object> strategy = new LinkedHashMap<>();
        try {
            JsonNode plan = ai.generateJson(prompts.forCyclePlan(
                    identity.identityBlock(tenantId, companyId),
                    memory == null ? null : memory.compressedContext(),
                    memory == null ? List.of() : memory.operatorNotes().stream().map(Note::text).toList(),
                    approved.map(s -> truncate(s, 2000)).orElse(null),
                    waiting, experiments.recentResults(tenantId, companyId, 5)), PromptRegistry.CYCLE_PLAN_SCHEMA);
            strategy.put("objective", plan.path("objective").asText());
            strategy.put("rationale", plan.path("rationale").asText());
            List<Map<String, String>> posts = new ArrayList<>();
            plan.path("posts").forEach(p -> posts.add(Map.of("angle", p.path("angle").asText(), "brief", p.path("brief").asText())));
            strategy.put("posts", posts);
            strategy.put("drafts", draft(companyId, posts, waiting));
            strategy.put("source", "ai:cycle-plan");
        } catch (ApiException e) {
            log.warn("Cycle plan for company {} fell back to the fixed plan: {}", companyId, e.getMessage());
            strategy.put("objective", "Grow qualified leads while holding CPL steady");
            strategy.put("initiatives", List.of(
                    "Tighten audience targeting on paid search",
                    "Launch a 7-email nurture sequence",
                    "Refresh top landing page above the fold"));
            strategy.put("aiSkipped", e.getMessage());
            strategy.put("source", "fixed:innovation");
        }
        approved.ifPresent(s -> {
            strategy.put("strategistInput", truncate(s, 2000));
            strategy.put("basedOn", "strategist:approved");
        });

        // What this cycle carried over from the last one (Boot).
        if (memory != null) {
            Map<String, Object> carried = new LinkedHashMap<>();
            carried.put("previousCycle", memory.previousIterationNo());
            carried.put("pendingTasks", memory.carriedOverTasks().size());
            carried.put("ownerNotes", memory.operatorNotes().size());
            strategy.put("carriedOver", carried);
        }

        context.put("strategy", strategy);
        return strategy;
    }

    /** Turns the plan's best ideas into drafts for approval, within the per-cycle and waiting limits. */
    private Map<String, Object> draft(Long companyId, List<Map<String, String>> posts, long waiting) {
        Map<String, Object> out = new LinkedHashMap<>();
        long room = Math.max(0, maxPendingDrafts - waiting);
        int count = (int) Math.min(Math.min(draftsPerCycle, room), posts.size());
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Map<String, String> post = posts.get(i);
            try {
                GenerateResponse drafted = generation.generateForCycle(companyId, DRAFT_TYPE,
                        post.get("angle") + ". " + post.get("brief"));
                ids.add(drafted.english().id());
            } catch (ApiException e) {
                out.put("problem", e.getMessage());
                break;
            }
        }
        out.put("created", ids);
        if (count == 0 && !posts.isEmpty()) {
            out.put("skipped", draftsPerCycle == 0 ? "drafting is off (cmo2.loop.drafts-per-cycle=0)"
                    : waiting + " drafts are already waiting for approval");
        }
        return out;
    }

    private String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
