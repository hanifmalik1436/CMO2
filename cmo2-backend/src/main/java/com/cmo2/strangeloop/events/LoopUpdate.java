package com.cmo2.strangeloop.events;

import java.time.Instant;

/**
 * Real-time payload pushed over STOMP to {@code /topic/tenant.{tenantId}.loop}.
 * The frontend drives the live stage progress bars from this.
 */
public record LoopUpdate(
        String type,            // ITERATION_STARTED, STAGE_STARTED, STAGE_COMPLETED, ITERATION_COMPLETED, ITERATION_FAILED
        Long iterationId,
        Long companyId,
        int iterationNo,
        Integer stageNo,        // null for iteration-level events
        String stageName,       // null for iteration-level events
        String stageStatus,     // null for iteration-level events
        String iterationStatus,
        int progressPct,        // overall iteration progress 0..100
        String message,
        Instant at
) {}
