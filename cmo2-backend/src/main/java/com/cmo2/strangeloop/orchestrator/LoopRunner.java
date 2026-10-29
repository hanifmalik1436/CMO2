package com.cmo2.strangeloop.orchestrator;

import com.cmo2.ai.AiCallScope;
import com.cmo2.shared.security.TenantContext;
import com.cmo2.strangeloop.memory.CycleMemoryService;
import com.cmo2.strangeloop.domain.StageName;
import com.cmo2.strangeloop.events.LoopEventBroadcaster;
import com.cmo2.strangeloop.stages.LoopStage;
import com.cmo2.strangeloop.stages.StageContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Strange Loop engine. Runs stages 1→5 off the request thread, committing each
 * transition independently so a crash resumes from the first incomplete stage.
 * Emits WebSocket progress at every transition.
 */
@Component
public class LoopRunner {

    private static final Logger log = LoggerFactory.getLogger(LoopRunner.class);
    private static final int STAGE_COUNT = 5;

    private final List<LoopStage> orderedStages;
    private final LoopPersistenceService persistence;
    private final LoopEventBroadcaster broadcaster;
    private final TaskExecutor taskExecutor;
    private final CycleMemoryService memory;

    @Value("${cmo2.loop.stage-delay-ms:600}")
    private long stageDelayMs;

    public LoopRunner(List<LoopStage> stages,
                      LoopPersistenceService persistence,
                      LoopEventBroadcaster broadcaster,
                      @org.springframework.beans.factory.annotation.Qualifier("loopTaskExecutor") TaskExecutor taskExecutor,
                      CycleMemoryService memory) {
        this.orderedStages = stages.stream()
                .sorted(Comparator.comparingInt(s -> s.name().number))
                .toList();
        this.persistence = persistence;
        this.broadcaster = broadcaster;
        this.taskExecutor = taskExecutor;
        this.memory = memory;
    }

    /** Schedule the cycle to run asynchronously. */
    public void submit(Long iterationId, Long tenantId, Long companyId, int iterationNo) {
        taskExecutor.execute(() -> execute(iterationId, tenantId, companyId, iterationNo));
    }

    /** The cycle body. Tenant context is set from the iteration's tenant (no request thread here). */
    public void execute(Long iterationId, Long tenantId, Long companyId, int iterationNo) {
        TenantContext.set(tenantId, null);
        try {
            Set<Integer> done = persistence.completedStageNumbers(iterationId);
            StageContext ctx = new StageContext(companyId, iterationId, iterationNo);
            // Boot: start from the last cycle's record and what the owner did since.
            ctx.put("memory", memory.boot(tenantId, companyId, iterationId));

            for (LoopStage stage : orderedStages) {
                int n = stage.name().number;
                if (done.contains(n)) {
                    log.debug("Resuming iteration {}: skipping already-complete stage {}", iterationId, n);
                    continue;
                }

                broadcaster.send(tenantId, broadcaster.stageStarted(
                        iterationId, companyId, iterationNo, n, stage.name().title, ((n - 1) * 100) / STAGE_COUNT));

                Long stageRunId = persistence.beginStage(iterationId, stage.name());
                sleepForDemo();
                Map<String, Object> data;
                try (AiCallScope scope = AiCallScope.forStage(companyId, iterationId, stage.name().name())) {
                    data = stage.run(ctx);
                }
                persistence.completeStage(stageRunId, data);

                // Persist the two iteration-level artifacts the architecture calls out.
                if (stage.name() == StageName.LEARNING) {
                    persistence.saveInsights(iterationId, data);
                } else if (stage.name() == StageName.REPORTING) {
                    persistence.saveGaps(iterationId, data);
                }

                broadcaster.send(tenantId, broadcaster.stageCompleted(
                        iterationId, companyId, iterationNo, n, stage.name().title, (n * 100) / STAGE_COUNT));
            }

            persistence.completeIteration(iterationId);
            broadcaster.send(tenantId, broadcaster.iterationCompleted(iterationId, companyId, iterationNo));
            // The next cycle comes from the customer's own schedule (LoopSchedules / Quartz), not from here.

        } catch (Exception e) {
            log.error("Loop iteration {} failed", iterationId, e);
            try {
                persistence.failIteration(iterationId, e.getMessage());
                broadcaster.send(tenantId, broadcaster.iterationFailed(
                        iterationId, companyId, iterationNo, "Cycle failed: " + e.getMessage()));
            } catch (Exception ignored) {
                // never let failure handling throw
            }
        } finally {
            TenantContext.clear();
        }
    }

    private void sleepForDemo() {
        if (stageDelayMs <= 0) return;
        try {
            Thread.sleep(stageDelayMs); // makes the live progress bars visible; set 0 in prod
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
