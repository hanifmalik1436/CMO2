package com.cmo2.strangeloop.repository;

import com.cmo2.strangeloop.domain.IterationStatus;
import com.cmo2.strangeloop.domain.TriggerType;
import com.cmo2.strangeloop.domain.LoopIteration;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LoopIterationRepository extends JpaRepository<LoopIteration, Long> {

    List<LoopIteration> findByCompanyIdOrderByIterationNoDesc(Long companyId);

    Optional<LoopIteration> findTopByCompanyIdOrderByIterationNoDesc(Long companyId);

    /**
     * Used by the crash-resume runner at startup. Runs with NO tenant context, so the
     * tenant filter is disabled and this sweeps in-flight iterations across all tenants.
     */
    List<LoopIteration> findByStatus(IterationStatus status);

    boolean existsByTenantIdAndCompanyIdAndStatus(Long tenantId, Long companyId, IterationStatus status);

    Optional<LoopIteration> findFirstByTenantIdAndCompanyIdAndTriggerTypeOrderByStartedAtDesc(
            Long tenantId, Long companyId, TriggerType triggerType);
}
