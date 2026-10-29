package com.cmo2.strangeloop.orchestrator;

import com.cmo2.shared.exceptions.ApiException;
import com.cmo2.strangeloop.domain.IterationStatus;
import com.cmo2.strangeloop.domain.LoopIteration;
import com.cmo2.strangeloop.domain.LoopStageRun;
import com.cmo2.strangeloop.domain.StageName;
import com.cmo2.strangeloop.domain.StageStatus;
import com.cmo2.strangeloop.domain.TriggerType;
import com.cmo2.strangeloop.repository.LoopIterationRepository;
import com.cmo2.strangeloop.repository.LoopStageRunRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Small, single-responsibility transactional methods used by the (non-transactional,
 * async) {@link LoopRunner}. Keeping each stage transition in its own committed
 * transaction is what makes crash-resume possible: a row reaches COMPLETED on disk
 * the moment the stage finishes.
 */
@Service
public class LoopPersistenceService {

    private final LoopIterationRepository iterationRepo;
    private final LoopStageRunRepository stageRepo;
    private final ObjectMapper objectMapper;

    public LoopPersistenceService(LoopIterationRepository iterationRepo,
                                  LoopStageRunRepository stageRepo,
                                  ObjectMapper objectMapper) {
        this.iterationRepo = iterationRepo;
        this.stageRepo = stageRepo;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public LoopIteration createIteration(Long companyId, TriggerType triggerType) {
        int nextNo = iterationRepo.findTopByCompanyIdOrderByIterationNoDesc(companyId)
                .map(LoopIteration::getIterationNo).orElse(0) + 1;

        LoopIteration iteration = new LoopIteration();
        iteration.setCompanyId(companyId);
        iteration.setIterationNo(nextNo);
        iteration.setStatus(IterationStatus.RUNNING);
        iteration.setTriggerType(triggerType);
        iteration.setStartedAt(Instant.now());
        return iterationRepo.save(iteration);
    }

    @Transactional(readOnly = true)
    public LoopIteration getIteration(Long iterationId) {
        return iterationRepo.findById(iterationId)
                .orElseThrow(() -> ApiException.notFound("Loop iteration not found"));
    }

    @Transactional(readOnly = true)
    public Set<Integer> completedStageNumbers(Long iterationId) {
        return stageRepo.findByIterationIdOrderByStageNo(iterationId).stream()
                .filter(s -> s.getStatus() == StageStatus.COMPLETED)
                .map(LoopStageRun::getStageNo)
                .collect(Collectors.toSet());
    }

    /** Creates (or reuses, on resume) the stage run row and marks it RUNNING. */
    @Transactional
    public Long beginStage(Long iterationId, StageName stage) {
        LoopStageRun run = stageRepo.findByIterationIdAndStageNo(iterationId, stage.number)
                .orElseGet(LoopStageRun::new);
        run.setIterationId(iterationId);
        run.setStageNo(stage.number);
        run.setStageName(stage);
        run.setStatus(StageStatus.RUNNING);
        run.setProgressPct(10);
        run.setStartedAt(Instant.now());
        return stageRepo.save(run).getId();
    }

    @Transactional
    public void completeStage(Long stageRunId, Map<String, Object> data) {
        LoopStageRun run = stageRepo.findById(stageRunId)
                .orElseThrow(() -> ApiException.notFound("Stage run not found"));
        run.setStatus(StageStatus.COMPLETED);
        run.setProgressPct(100);
        run.setCompletedAt(Instant.now());
        run.setDataJson(toJson(data));
        stageRepo.save(run);
    }

    @Transactional
    public void saveInsights(Long iterationId, Map<String, Object> insights) {
        LoopIteration it = getIteration(iterationId);
        it.setInsightsJson(toJson(insights));
        iterationRepo.save(it);
    }

    @Transactional
    public void saveGaps(Long iterationId, Map<String, Object> gaps) {
        LoopIteration it = getIteration(iterationId);
        it.setGapsJson(toJson(gaps));
        iterationRepo.save(it);
    }

    @Transactional
    public void completeIteration(Long iterationId) {
        LoopIteration it = getIteration(iterationId);
        it.setStatus(IterationStatus.COMPLETED);
        it.setCompletedAt(Instant.now());
        iterationRepo.save(it);
    }

    @Transactional
    public void failIteration(Long iterationId, String message) {
        LoopIteration it = getIteration(iterationId);
        it.setStatus(IterationStatus.FAILED);
        it.setErrorMessage(message != null && message.length() > 1000 ? message.substring(0, 1000) : message);
        iterationRepo.save(it);
    }

    private String toJson(Map<String, Object> data) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
