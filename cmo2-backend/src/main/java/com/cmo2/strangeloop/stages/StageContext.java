package com.cmo2.strangeloop.stages;

import java.util.HashMap;
import java.util.Map;

/**
 * Carries data between stages within one iteration. Boot puts the customer's "memory" (last
 * cycle's record and the owner's notes) in first; Stage 1 writes "strategy", Stage 2 writes
 * "insights", and so on, mirroring the orchestrator pipeline in the architecture doc.
 */
public class StageContext {

    private final Long companyId;
    private final Long iterationId;
    private final int iterationNo;
    private final Map<String, Object> shared = new HashMap<>();

    public StageContext(Long companyId, int iterationNo) {
        this(companyId, null, iterationNo);
    }

    public StageContext(Long companyId, Long iterationId, int iterationNo) {
        this.companyId = companyId;
        this.iterationId = iterationId;
        this.iterationNo = iterationNo;
    }

    public Long companyId() {
        return companyId;
    }

    public Long iterationId() {
        return iterationId;
    }

    public int iterationNo() {
        return iterationNo;
    }

    public void put(String key, Object value) {
        shared.put(key, value);
    }

    public Object get(String key) {
        return shared.get(key);
    }
}
