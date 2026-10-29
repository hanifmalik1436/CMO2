package com.cmo2.strangeloop.orchestrator;

import com.cmo2.strangeloop.domain.IterationStatus;
import com.cmo2.strangeloop.domain.LoopIteration;
import com.cmo2.strangeloop.repository.LoopIterationRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Transactional helper for the startup crash-resume sweep. Reading runs in its own
 * transaction (the repository call needs an active persistence context) and with no
 * tenant context bound, so the tenant filter is off and it sees every tenant's
 * in-flight iterations.
 */
@Component
public class InFlightIterationFinder {

    private final LoopIterationRepository repository;

    public InFlightIterationFinder(LoopIterationRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<LoopIteration> findRunning() {
        return repository.findByStatus(IterationStatus.RUNNING);
    }
}
