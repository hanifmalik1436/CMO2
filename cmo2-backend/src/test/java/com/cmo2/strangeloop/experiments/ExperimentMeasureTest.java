package com.cmo2.strangeloop.experiments;

import com.cmo2.integrations.core.Metrics;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExperimentMeasureTest {

    private static Experiment experiment(double baseline, String expected) {
        Experiment e = new Experiment();
        e.setId(7L);
        e.setHypothesis("h");
        e.setMetric(Metrics.LEADS);
        e.setExpectedDirection(expected);
        e.setBaselineValue(baseline);
        return e;
    }

    @Test
    void anExperimentIsJudgedAgainstItsBaselineWithAFlatBand() {
        Experiment up = experiment(20, "up");
        ExperimentService.measure(up, 26);
        assertThat(up.getOutcome()).isEqualTo("SUPPORTED");
        assertThat(up.getStatus()).isEqualTo(ExperimentStatus.MEASURED);

        Experiment wrongWay = experiment(20, "up");
        ExperimentService.measure(wrongWay, 15);
        assertThat(wrongWay.getOutcome()).isEqualTo("NOT_SUPPORTED");

        Experiment flat = experiment(20, "up");
        ExperimentService.measure(flat, 20.5); // +2.5% is inside the flat band
        assertThat(flat.getOutcome()).isEqualTo("INCONCLUSIVE");
        assertThat(flat.getResultSummary()).contains("no clear change").contains("signal, not proof");

        Experiment down = experiment(10, "down");
        ExperimentService.measure(down, 6);
        assertThat(down.getOutcome()).isEqualTo("SUPPORTED");
    }
}
