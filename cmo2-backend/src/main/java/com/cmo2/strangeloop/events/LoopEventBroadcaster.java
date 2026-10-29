package com.cmo2.strangeloop.events;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Pushes loop progress to the per-tenant STOMP topic. Each tenant only ever
 * receives its own topic ({@code /topic/tenant.{tenantId}.loop}); subscription to
 * another tenant's topic is rejected by the WebSocket auth interceptor.
 */
@Component
public class LoopEventBroadcaster {

    private final SimpMessagingTemplate messaging;

    public LoopEventBroadcaster(SimpMessagingTemplate messaging) {
        this.messaging = messaging;
    }

    public void send(Long tenantId, LoopUpdate update) {
        messaging.convertAndSend("/topic/tenant." + tenantId + ".loop", update);
    }

    public LoopUpdate iterationStarted(Long iterationId, Long companyId, int iterationNo) {
        return new LoopUpdate("ITERATION_STARTED", iterationId, companyId, iterationNo,
                null, null, null, "RUNNING", 0, "Cycle started", Instant.now());
    }

    public LoopUpdate stageStarted(Long iterationId, Long companyId, int iterationNo,
                                   int stageNo, String stageName, int overallPct) {
        return new LoopUpdate("STAGE_STARTED", iterationId, companyId, iterationNo,
                stageNo, stageName, "RUNNING", "RUNNING", overallPct,
                "Stage " + stageNo + " running", Instant.now());
    }

    public LoopUpdate stageCompleted(Long iterationId, Long companyId, int iterationNo,
                                     int stageNo, String stageName, int overallPct) {
        return new LoopUpdate("STAGE_COMPLETED", iterationId, companyId, iterationNo,
                stageNo, stageName, "COMPLETED", "RUNNING", overallPct,
                "Stage " + stageNo + " complete", Instant.now());
    }

    public LoopUpdate iterationCompleted(Long iterationId, Long companyId, int iterationNo) {
        return new LoopUpdate("ITERATION_COMPLETED", iterationId, companyId, iterationNo,
                null, null, null, "COMPLETED", 100, "Cycle complete", Instant.now());
    }

    public LoopUpdate iterationFailed(Long iterationId, Long companyId, int iterationNo, String message) {
        return new LoopUpdate("ITERATION_FAILED", iterationId, companyId, iterationNo,
                null, null, null, "FAILED", 0, message, Instant.now());
    }
}
