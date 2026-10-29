package com.cmo2.strangeloop.stages;

import com.cmo2.ai.AiService;
import com.cmo2.ai.JsonSchemas;
import com.cmo2.ai.PromptRegistry;
import com.cmo2.analytics.service.KpiTrendService;
import com.cmo2.analytics.service.KpiTrendService.Trend;
import com.cmo2.customers.service.CustomerIdentityService;
import com.cmo2.integrations.core.Metrics;
import com.cmo2.shared.exceptions.ApiException;
import com.cmo2.shared.security.TenantContext;
import com.cmo2.strangeloop.domain.StageName;
import com.cmo2.strangeloop.experiments.ExperimentService;
import com.cmo2.strangeloop.learning.LearningService;
import com.cmo2.strangeloop.learning.LearningService.Hypothesis;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Stage 2 — Learning. First measures experiments applied in earlier cycles against their baselines.
 * Then Java compares the customer's last 7 days with the 7 before (daily KPI rollups);
 * only that compact comparison, plus earlier experiment results, goes to the strategy model, which
 * returns observations and testable hypotheses (structured output; hypotheses may only name metrics
 * that are in the figures). The result is saved as a learning record. Without figures there is
 * nothing to learn from and no AI call; without AI the observations are the plain comparisons.
 */
@Service
public class Stage2Learning implements LoopStage {

    private static final Logger log = LoggerFactory.getLogger(Stage2Learning.class);

    private final KpiTrendService trends;
    private final AiService ai;
    private final PromptRegistry prompts;
    private final CustomerIdentityService identity;
    private final LearningService learning;
    private final ExperimentService experiments;
    private final Duration measureAfter;

    public Stage2Learning(KpiTrendService trends, AiService ai, PromptRegistry prompts, CustomerIdentityService identity,
                          LearningService learning, ExperimentService experiments,
                          @Value("${cmo2.loop.experiment-measure-after-days:3}") int measureAfterDays) {
        this.trends = trends;
        this.ai = ai;
        this.prompts = prompts;
        this.identity = identity;
        this.learning = learning;
        this.experiments = experiments;
        this.measureAfter = Duration.ofDays(Math.max(0, measureAfterDays));
    }

    @Override
    public StageName name() {
        return StageName.LEARNING;
    }

    @Override
    public Map<String, Object> run(StageContext context) {
        Long tenantId = TenantContext.requireTenantId();
        Long companyId = context.companyId();
        List<Trend> week = trends.weekOverWeek(tenantId, companyId);
        int dataPoints = trends.dataPoints(tenantId, companyId, 14);
        // Measure applied experiments first, so this reading (and Stage 5's proposals) can use the results.
        List<String> measured = experiments.measureDue(tenantId, companyId,
                week.stream().collect(Collectors.toMap(Trend::metric, Function.identity(), (a, b) -> a)), measureAfter);

        List<Map<String, Object>> patterns = new ArrayList<>();
        List<String> comparisons = new ArrayList<>();
        int compared = 0;
        for (Trend t : week) {
            comparisons.add(describe(t));
            if (t.previous() == null) continue;
            compared++;
            patterns.add(pattern(t));
        }

        List<String> observations;
        List<Hypothesis> hypotheses = List.of();
        double confidence;
        String source;
        String note = null;
        if (week.isEmpty()) {
            observations = List.of("No KPI data for this customer yet. It arrives once their GoHighLevel sub-account syncs.");
            confidence = 0;
            source = "no-data";
        } else {
            // Share of metrics that have a previous week to compare with.
            double coverage = Math.round(100.0 * compared / week.size()) / 100.0;
            try {
                JsonNode reading = ai.generateJson(prompts.forLearning(identity.identityBlock(tenantId, companyId),
                                String.join("\n", comparisons), experiments.recentResults(tenantId, companyId, 5)),
                        schema(week));
                List<String> read = new ArrayList<>();
                reading.path("observations").forEach(o -> read.add(o.asText()));
                observations = read;
                List<Hypothesis> hs = new ArrayList<>();
                reading.path("hypotheses").forEach(h -> hs.add(new Hypothesis(h.path("statement").asText(),
                        h.path("metric").asText(), h.path("expectedDirection").asText(), h.path("confidence").asDouble())));
                hypotheses = hs;
                // The model's confidence can't exceed what the data covers.
                confidence = Math.min(coverage, reading.path("confidence").asDouble(coverage));
                source = "ai";
            } catch (ApiException e) {
                log.warn("Learning for company {} fell back to plain comparisons: {}", companyId, e.getMessage());
                observations = comparisons;
                confidence = coverage;
                source = "rules";
                note = "AI not used: " + e.getMessage();
            }
        }

        learning.record(tenantId, companyId, context.iterationId(), context.iterationNo(), source, patterns,
                observations, hypotheses, confidence, note);

        Map<String, Object> insights = new LinkedHashMap<>();
        insights.put("dataPoints", dataPoints);
        insights.put("confidence", confidence);
        insights.put("observations", observations);
        insights.put("hypotheses", hypotheses);
        insights.put("experimentsMeasured", measured);
        insights.put("patterns", patterns);
        insights.put("source", "learning:" + source);
        if (note != null) insights.put("note", note);
        context.put("insights", insights);
        context.put("trends", week);
        return insights;
    }

    /** Observations, up to three hypotheses on metrics that are actually in the figures, and confidence. */
    static Map<String, Object> schema(List<Trend> week) {
        List<String> metrics = week.stream().map(Trend::metric).toList();
        return JsonSchemas.object(Map.of(
                "observations", JsonSchemas.array(JsonSchemas.string(300, null), 5, "What the figures show, plainly"),
                "hypotheses", JsonSchemas.array(JsonSchemas.object(Map.of(
                        "statement", JsonSchemas.string(300, "A testable explanation or idea"),
                        "metric", JsonSchemas.enumOf(metrics, "The metric it is about"),
                        "expectedDirection", JsonSchemas.enumOf(List.of("up", "down"), "Where that metric should move if it's right"),
                        "confidence", JsonSchemas.number(0, 1, null))), 3, null),
                "confidence", JsonSchemas.number(0, 1, "Overall confidence in this reading")));
    }

    private static Map<String, Object> pattern(Trend t) {
        Double change = change(t);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("metric", t.metric());
        p.put("current", t.current());
        p.put("previous", t.previous());
        p.put("changePct", change == null ? null : Math.round(change * 10.0) / 10.0);
        p.put("direction", t.current() > t.previous() ? "up" : t.current() < t.previous() ? "down" : "flat");
        return p;
    }

    static Double change(Trend t) {
        return t.previous() == null || t.previous() == 0 ? null : 100.0 * (t.current() - t.previous()) / t.previous();
    }

    static String describe(Trend t) {
        String label = Metrics.label(t.metric()) + (t.daily() ? " (7-day total)" : " (latest)");
        if (t.previous() == null) {
            return label + ": " + num(t.current()) + " (nothing to compare with yet)";
        }
        Double change = change(t);
        return label + ": " + num(t.current()) + " vs " + num(t.previous()) + " the week before"
                + (change == null ? "" : " (" + (change >= 0 ? "+" : "") + num(change) + "%)");
    }

    private static String num(double v) {
        return v == Math.rint(v) ? String.format(Locale.ROOT, "%,d", (long) v) : String.format(Locale.ROOT, "%,.1f", v);
    }
}
