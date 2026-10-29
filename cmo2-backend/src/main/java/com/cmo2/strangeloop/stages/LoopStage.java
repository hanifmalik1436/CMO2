package com.cmo2.strangeloop.stages;

import com.cmo2.strangeloop.domain.StageName;

import java.util.Map;

/**
 * One stage of the Strange Loop. Implementations are stateless services.
 *
 * <p>Phase 2 ships deliberate stub logic with real persistence + events: each
 * stage produces structured output (stored as JSON) so the full cycle runs and
 * resumes correctly. Later phases replace the stub bodies with real AI / KPI /
 * documentation logic without touching the orchestration around them.
 */
public interface LoopStage {

    StageName name();

    /** Runs the stage and returns its structured output (serialized to data_json). */
    Map<String, Object> run(StageContext context);
}
