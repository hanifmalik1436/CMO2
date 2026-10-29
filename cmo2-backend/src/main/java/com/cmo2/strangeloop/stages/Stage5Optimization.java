package com.cmo2.strangeloop.stages;

import com.cmo2.ai.AiService;
import com.cmo2.ai.JsonSchemas;
import com.cmo2.ai.PromptRegistry;
import com.cmo2.analytics.service.KpiTrendService;
import com.cmo2.analytics.service.KpiTrendService.Trend;
import com.cmo2.assets.service.AssetActivityService;
import com.cmo2.customers.service.CustomerIdentityService;
import com.cmo2.integrations.gohighlevel.GhlCustomValues;
import com.cmo2.integrations.gohighlevel.GhlCustomValues.CustomValue;
import com.cmo2.shared.exceptions.ApiException;
import com.cmo2.shared.notify.TeamNotifier;
import com.cmo2.shared.security.TenantContext;
import com.cmo2.strangeloop.domain.StageName;
import com.cmo2.strangeloop.experiments.Experiment;
import com.cmo2.strangeloop.experiments.ExperimentAction;
import com.cmo2.strangeloop.experiments.ExperimentService;
import com.cmo2.strangeloop.experiments.ExperimentService.Proposal;
import com.cmo2.strangeloop.learning.LearningService.Hypothesis;
import com.cmo2.tenancy.repository.CompanyRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Stage 5 — Optimization. The strategy model proposes up to two experiments (Stage 2 has already
 * measured earlier ones), each a hypothesis tied to one metric and one
 * action from {@link ExperimentAction}: the structured-output schema only allows the actions on
 * offer this cycle, metrics that are in the figures, and the sub-account's real custom value ids.
 *
 * <p>Nothing client-facing runs from here: a social post becomes a draft for Approvals, a custom value
 * change waits for the owner (unless {@code cmo2.loop.auto-apply-reversible} is on), and a
 * suggestion is a task for the owner. No new proposals while {@code cmo2.loop.max-open-experiments}
 * wait for a decision.
 */
@Service
public class Stage5Optimization implements LoopStage {

    private static final Logger log = LoggerFactory.getLogger(Stage5Optimization.class);
    static final int MAX_PROPOSALS = 2;

    private final KpiTrendService trends;
    private final AiService ai;
    private final PromptRegistry prompts;
    private final CustomerIdentityService identity;
    private final ExperimentService experiments;
    private final GhlCustomValues customValues;
    private final AssetActivityService assets;
    private final TeamNotifier team;
    private final CompanyRepository companies;
    private final int maxOpen;
    private final int maxPendingDrafts;
    private final boolean autoApplyReversible;

    public Stage5Optimization(KpiTrendService trends, AiService ai, PromptRegistry prompts, CustomerIdentityService identity,
                              ExperimentService experiments, GhlCustomValues customValues, AssetActivityService assets,
                              TeamNotifier team, CompanyRepository companies,
                              @Value("${cmo2.loop.max-open-experiments:3}") int maxOpen,
                              @Value("${cmo2.loop.max-pending-drafts:3}") int maxPendingDrafts,
                              @Value("${cmo2.loop.auto-apply-reversible:false}") boolean autoApplyReversible) {
        this.trends = trends;
        this.ai = ai;
        this.prompts = prompts;
        this.identity = identity;
        this.experiments = experiments;
        this.customValues = customValues;
        this.assets = assets;
        this.team = team;
        this.companies = companies;
        this.maxOpen = Math.max(0, maxOpen);
        this.maxPendingDrafts = Math.max(0, maxPendingDrafts);
        this.autoApplyReversible = autoApplyReversible;
    }

    @Override
    public StageName name() {
        return StageName.OPTIMIZATION;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> run(StageContext context) {
        Long tenantId = TenantContext.requireTenantId();
        Long companyId = context.companyId();
        List<Trend> week = context.get("trends") instanceof List<?> l ? (List<Trend>) l
                : trends.weekOverWeek(tenantId, companyId);
        Map<String, Trend> byMetric = week.stream().collect(Collectors.toMap(Trend::metric, Function.identity(), (a, b) -> a));

        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> proposed = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        out.put("proposed", proposed);
        out.put("skipped", skipped);
        out.put("source", "optimization:experiments");

        if (week.isEmpty()) {
            out.put("note", "No figures yet, so nothing to experiment on or measure against.");
            return out;
        }
        long room = Math.min(MAX_PROPOSALS, maxOpen - experiments.openCount(tenantId, companyId));
        if (room <= 0) {
            out.put("note", "No new experiments: " + maxOpen + " are already waiting for your decision.");
            return out;
        }

        Map<String, CustomValue> customs = customValueMap(companyId, skipped);
        List<ExperimentAction> menu = new ArrayList<>(List.of(ExperimentAction.SUGGESTION));
        if (assets.awaitingReviewCount(tenantId, companyId) < maxPendingDrafts) menu.add(0, ExperimentAction.DRAFT_SOCIAL_POST);
        if (!customs.isEmpty()) menu.add(ExperimentAction.UPDATE_CUSTOM_VALUE);

        JsonNode answer;
        try {
            answer = ai.generateJson(prompts.forOptimization(identity.identityBlock(tenantId, companyId),
                    String.join("\n", week.stream().map(Stage2Learning::describe).toList()),
                    hypotheses(context), Stage4Reporting.describe(gaps(context)), menuText(menu, customs),
                    experiments.recentResults(tenantId, companyId, 5)), schema(menu, byMetric.keySet(), customs.keySet(), room));
        } catch (ApiException e) {
            log.warn("Optimization for company {} proposed nothing: {}", companyId, e.getMessage());
            out.put("aiSkipped", e.getMessage());
            return out;
        }

        for (JsonNode p : answer.path("proposals")) {
            if (proposed.size() >= room) break;
            Proposal proposal = toProposal(p, customs);
            String problem = problem(proposal, menu, byMetric, customs);
            if (problem != null) {
                skipped.add(problem);
                continue;
            }
            try {
                Experiment e = experiments.propose(tenantId, companyId, context.iterationId(), proposal,
                        byMetric.get(proposal.metric()).current(), autoApplyReversible);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", e.getId());
                row.put("action", e.getActionType().name());
                row.put("hypothesis", e.getHypothesis());
                row.put("status", e.getStatus().name());
                if (e.getAssetId() != null) row.put("draftId", e.getAssetId());
                proposed.add(row);
            } catch (ApiException e) {
                skipped.add(proposal.action() + ": " + e.getMessage());
            }
        }
        if (!proposed.isEmpty()) {
            String company = companies.findById(companyId).map(c -> c.getName()).orElse("a customer");
            team.notify(tenantId, ":test_tube: " + proposed.size() + (proposed.size() == 1 ? " experiment" : " experiments")
                    + " proposed for " + company + ". Review them on the customer's page in CMO² (drafts in Approvals).");
        }
        return out;
    }

    private Map<String, CustomValue> customValueMap(Long companyId, List<String> skipped) {
        try {
            return customValues.list(companyId).stream()
                    .collect(Collectors.toMap(CustomValue::id, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        } catch (RuntimeException e) {
            skipped.add("Couldn't read GHL custom values: " + e.getMessage());
            return Map.of();
        }
    }

    static Map<String, Object> schema(List<ExperimentAction> menu, Collection<String> metrics,
                                      Collection<String> customValueIds, long room) {
        List<String> ids = new ArrayList<>(List.of(""));
        ids.addAll(customValueIds);
        return JsonSchemas.object(Map.of("proposals", JsonSchemas.array(JsonSchemas.object(Map.of(
                "hypothesis", JsonSchemas.string(300, "What you expect and why"),
                "action", JsonSchemas.enumOf(menu.stream().map(Enum::name).toList(), "One of the offered actions"),
                "metric", JsonSchemas.enumOf(List.copyOf(metrics), "The metric this should move"),
                "expectedDirection", JsonSchemas.enumOf(List.of("up", "down"), null),
                "postBrief", JsonSchemas.string(1500, "DRAFT_SOCIAL_POST only: message, audience, call to action"),
                "customValueId", JsonSchemas.enumOf(ids, "UPDATE_CUSTOM_VALUE only: which custom value"),
                "newValue", JsonSchemas.string(5000, "UPDATE_CUSTOM_VALUE only: its new text"),
                "suggestion", JsonSchemas.string(500, "SUGGESTION only: what the owner should do"))), (int) room, null)));
    }

    private static String menuText(List<ExperimentAction> menu, Map<String, CustomValue> customs) {
        StringBuilder s = new StringBuilder();
        for (ExperimentAction a : menu) {
            s.append("- ").append(a.name()).append(": ").append(switch (a) {
                case DRAFT_SOCIAL_POST -> "draft a social post (fill postBrief). The owner approves and publishes it.";
                case UPDATE_CUSTOM_VALUE -> "replace the text of one existing GoHighLevel custom value (fill customValueId and "
                        + "newValue). Their workflows and emails may use it; the owner approves the change.";
                case SUGGESTION -> "something for the owner to do themselves (fill suggestion).";
            }).append('\n');
        }
        if (!customs.isEmpty()) {
            s.append("Custom values (id: name = current text):\n");
            customs.values().forEach(c -> s.append("  ").append(c.id()).append(": ").append(c.name()).append(" = ")
                    .append(c.value() == null ? "" : c.value().replaceAll("\\s+", " ")).append('\n'));
        }
        return s.toString().trim();
    }

    private static Proposal toProposal(JsonNode p, Map<String, CustomValue> customs) {
        ExperimentAction action;
        try {
            action = ExperimentAction.valueOf(p.path("action").asText());
        } catch (IllegalArgumentException e) {
            action = null;
        }
        String customId = p.path("customValueId").asText("");
        CustomValue cv = customs.get(customId);
        return new Proposal(p.path("hypothesis").asText("").strip(), action, p.path("metric").asText(),
                p.path("expectedDirection").asText(), p.path("postBrief").asText("").strip(), customId,
                cv == null ? null : cv.name(), p.path("newValue").asText(""), p.path("suggestion").asText("").strip());
    }

    /** Why a proposal can't be used, or null. The schema already limits values; this checks what it can't. */
    static String problem(Proposal p, List<ExperimentAction> menu, Map<String, Trend> byMetric, Map<String, CustomValue> customs) {
        if (p.action() == null || !menu.contains(p.action())) return "an action that wasn't offered";
        if (p.hypothesis().isBlank()) return p.action() + " without a hypothesis";
        if (!byMetric.containsKey(p.metric())) return p.action() + " on a metric with no figures (" + p.metric() + ")";
        return switch (p.action()) {
            case DRAFT_SOCIAL_POST -> p.postBrief().isBlank() ? "a social post without a brief" : null;
            case UPDATE_CUSTOM_VALUE -> !customs.containsKey(p.customValueId()) ? "a custom value that doesn't exist"
                    : p.newValue().isBlank() ? "a custom value change without new text" : null;
            case SUGGESTION -> p.suggestion().isBlank() ? "an empty suggestion" : null;
        };
    }

    private static List<String> hypotheses(StageContext context) {
        if (context.get("insights") instanceof Map<?, ?> insights && insights.get("hypotheses") instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object h : list) {
                if (h instanceof Hypothesis hy) out.add(hy.statement() + " (" + hy.metric() + " " + hy.expectedDirection() + ")");
            }
            return out;
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> gaps(StageContext context) {
        return context.get("gaps") instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
    }
}
