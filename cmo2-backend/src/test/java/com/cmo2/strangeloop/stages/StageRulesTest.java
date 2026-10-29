package com.cmo2.strangeloop.stages;

import com.cmo2.analytics.service.KpiTrendService.Trend;
import com.cmo2.integrations.core.Metrics;
import com.cmo2.strangeloop.experiments.ExperimentAction;
import com.cmo2.strangeloop.experiments.ExperimentService.Proposal;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StageRulesTest {

    @Test
    void aRiseInCostOrUnreadConversationsIsAGap() {
        StageContext ctx = new StageContext(1L, 1L, 1);
        ctx.put("trends", List.of(
                new Trend(Metrics.CPL, false, 30, 20.0),                    // cost per lead up 50%: bad
                new Trend(Metrics.LEADS, true, 40, 20.0),                   // leads up: good
                new Trend(Metrics.CONVERSATIONS_UNREAD, false, 10, 9.8)));  // within 5%: ignored
        Map<String, Object> out = new Stage4Reporting(null).run(ctx);
        assertThat(out.get("gaps").toString()).contains(Metrics.CPL).doesNotContain("metric=" + Metrics.LEADS);
        assertThat(out.get("improving").toString()).contains("New leads");
    }

    @Test
    void proposalsTheSchemaCantCatchAreSkipped() {
        Map<String, Trend> figures = Map.of(Metrics.LEADS, new Trend(Metrics.LEADS, true, 10, 30.0));
        List<ExperimentAction> menu = List.of(ExperimentAction.SUGGESTION, ExperimentAction.DRAFT_SOCIAL_POST);

        assertThat(Stage5Optimization.problem(new Proposal("h", ExperimentAction.UPDATE_CUSTOM_VALUE, Metrics.LEADS, "up",
                "", "cv1", null, "x", ""), menu, figures, Map.of())).isEqualTo("an action that wasn't offered");
        assertThat(Stage5Optimization.problem(new Proposal("h", ExperimentAction.DRAFT_SOCIAL_POST, Metrics.LEADS, "up",
                "", "", null, "", ""), menu, figures, Map.of())).isEqualTo("a social post without a brief");
        assertThat(Stage5Optimization.problem(new Proposal("h", ExperimentAction.SUGGESTION, Metrics.REVENUE, "up",
                "", "", null, "", "Ask for reviews"), menu, figures, Map.of())).contains("no figures");
        assertThat(Stage5Optimization.problem(new Proposal("h", ExperimentAction.SUGGESTION, Metrics.LEADS, "up",
                "", "", null, "", "Ask for reviews"), menu, figures, Map.of())).isNull();
    }
}
