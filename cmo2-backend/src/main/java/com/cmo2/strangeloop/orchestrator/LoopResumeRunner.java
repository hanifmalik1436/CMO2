package com.cmo2.strangeloop.orchestrator;

import com.cmo2.strangeloop.domain.LoopIteration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * On startup, finds iterations left in RUNNING (i.e. the process died mid-cycle) and
 * resubmits them. {@link LoopRunner#execute} skips already-COMPLETED stages, so the
 * cycle continues from where it stopped rather than restarting — the Phase 2
 * "crash-resume, not restart" guarantee.
 */
@Component
@Order(100)
public class LoopResumeRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LoopResumeRunner.class);

    private final InFlightIterationFinder finder;
    private final LoopRunner runner;

    public LoopResumeRunner(InFlightIterationFinder finder, LoopRunner runner) {
        this.finder = finder;
        this.runner = runner;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<LoopIteration> inFlight = finder.findRunning();
        if (inFlight.isEmpty()) {
            return;
        }
        log.info("Resuming {} in-flight loop iteration(s) after restart", inFlight.size());
        for (LoopIteration it : inFlight) {
            runner.submit(it.getId(), it.getTenantId(), it.getCompanyId(), it.getIterationNo());
        }
    }
}
