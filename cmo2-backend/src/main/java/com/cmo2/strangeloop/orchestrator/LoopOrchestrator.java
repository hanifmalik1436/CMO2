package com.cmo2.strangeloop.orchestrator;

import com.cmo2.shared.security.TenantContext;
import com.cmo2.strangeloop.domain.LoopIteration;
import com.cmo2.strangeloop.domain.TriggerType;
import com.cmo2.strangeloop.events.LoopEventBroadcaster;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Entry point for launching a Strange Loop cycle. Creates the iteration in the
 * caller's tenant context, announces it, then hands off to the async {@link LoopRunner}.
 *
 * <p>Critically, the async hand-off is deferred until AFTER the surrounding
 * transaction commits — otherwise the background thread could try to read the
 * iteration row before it is visible and fail.
 */
@Service
public class LoopOrchestrator {

    private final LoopPersistenceService persistence;
    private final LoopRunner runner;
    private final LoopEventBroadcaster broadcaster;

    public LoopOrchestrator(LoopPersistenceService persistence,
                            LoopRunner runner,
                            LoopEventBroadcaster broadcaster) {
        this.persistence = persistence;
        this.runner = runner;
        this.broadcaster = broadcaster;
    }

    /**
     * Starts a cycle for {@code companyId}. A tenant MUST be bound in
     * {@link TenantContext} (the REST request, or set by the scheduler for SCHEDULED runs).
     */
    public LoopIteration startCycle(Long companyId, TriggerType triggerType) {
        Long tenantId = TenantContext.requireTenantId();
        LoopIteration iteration = persistence.createIteration(companyId, triggerType);

        Long iterationId = iteration.getId();
        int iterationNo = iteration.getIterationNo();
        Runnable launch = () -> {
            broadcaster.send(tenantId, broadcaster.iterationStarted(iterationId, companyId, iterationNo));
            runner.submit(iterationId, tenantId, companyId, iterationNo);
        };

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    launch.run();
                }
            });
        } else {
            // createIteration ran in its own committed transaction (e.g. scheduler path).
            launch.run();
        }
        return iteration;
    }
}
