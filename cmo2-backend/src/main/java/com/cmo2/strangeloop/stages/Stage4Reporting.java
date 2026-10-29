package com.cmo2.strangeloop.stages;

import com.cmo2.analytics.service.KpiTrendService;
import com.cmo2.analytics.service.KpiTrendService.Trend;
import com.cmo2.integrations.core.Metrics;
import com.cmo2.shared.security.TenantContext;
import com.cmo2.strangeloop.domain.StageName;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stage 4 — Reporting. Gaps are metrics moving the wrong way week over week (by at least
 * {@value #MIN_CHANGE_PERCENT}%), from the comparison Stage 2 made. No targets exist yet, so "wrong
 * way" means down for most metrics and up for the few where lower is better. Plain Java, no AI.
 */
@Service
public class Stage4Reporting implements LoopStage {

    static final double MIN_CHANGE_PERCENT = 5.0;
    /** Metrics where a rise is bad news. */
    static final Set<String> LOWER_IS_BETTER = Set.of(Metrics.CPL, Metrics.CONVERSATIONS_UNREAD, Metrics.OPPORTUNITIES_LOST_TOTAL);

    private final KpiTrendService trends;

    public Stage4Reporting(KpiTrendService trends) {
        this.trends = trends;
    }

    @Override
    public StageName name() {
        return StageName.REPORTING;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> run(StageContext context) {
        // Stage 2 leaves the comparison in the context; after a crash-resume it may not have run this time.
        List<Trend> week = context.get("trends") instanceof List<?> l ? (List<Trend>) l
                : trends.weekOverWeek(TenantContext.requireTenantId(), context.companyId());
        List<Map<String, Object>> gaps = new ArrayList<>();
        List<String> improving = new ArrayList<>();
        List<String> evaluated = new ArrayList<>();
        for (Trend t : week) {
            Double change = Stage2Learning.change(t);
            if (change == null) continue;
            evaluated.add(Metrics.label(t.metric()));
            if (Math.abs(change) < MIN_CHANGE_PERCENT) continue;
            boolean worse = LOWER_IS_BETTER.contains(t.metric()) ? change > 0 : change < 0;
            if (worse) {
                Map<String, Object> gap = new LinkedHashMap<>();
                gap.put("metric", t.metric());
                gap.put("label", Metrics.label(t.metric()));
                gap.put("current", t.current());
                gap.put("previous", t.previous());
                gap.put("changePct", Math.round(change * 10.0) / 10.0);
                gaps.add(gap);
            } else {
                improving.add(Metrics.label(t.metric()));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("targetsEvaluated", evaluated);
        out.put("gaps", gaps);
        out.put("improving", improving);
        if (evaluated.isEmpty()) out.put("note", "No week-over-week figures to compare yet.");
        out.put("source", "reporting:week-over-week");
        context.put("gaps", gaps);
        return out;
    }

    /** "New leads down 30% (14 → 10)" lines for prompts and records. */
    static List<String> describe(List<Map<String, Object>> gaps) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> g : gaps) {
            out.add(g.get("label") + " " + ((double) g.get("changePct") >= 0 ? "up " : "down ")
                    + Math.abs((double) g.get("changePct")) + "% (" + g.get("previous") + " → " + g.get("current") + ")");
        }
        return out;
    }
}
