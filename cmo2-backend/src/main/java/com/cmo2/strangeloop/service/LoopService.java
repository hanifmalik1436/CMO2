package com.cmo2.strangeloop.service;

import com.cmo2.shared.audit.AuditService;
import com.cmo2.shared.exceptions.ApiException;
import com.cmo2.shared.tenancy.TenantGuard;
import com.cmo2.strangeloop.domain.LoopIteration;
import com.cmo2.strangeloop.domain.TriggerType;
import com.cmo2.strangeloop.dto.LoopDtos.IterationDetailResponse;
import com.cmo2.strangeloop.dto.LoopDtos.IterationResponse;
import com.cmo2.strangeloop.dto.LoopDtos.StageRunResponse;
import com.cmo2.strangeloop.orchestrator.LoopOrchestrator;
import com.cmo2.strangeloop.repository.LoopIterationRepository;
import com.cmo2.strangeloop.repository.LoopStageRunRepository;
import com.cmo2.tenancy.repository.CompanyRepository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Façade for the Strange Loop module. Authorization is enforced here (service layer);
 * all reads are implicitly tenant-scoped by the Hibernate filter.
 */
@Service
public class LoopService {

    private final LoopOrchestrator orchestrator;
    private final LoopIterationRepository iterationRepo;
    private final LoopStageRunRepository stageRepo;
    private final CompanyRepository companyRepo;
    private final AuditService auditService;

    public LoopService(LoopOrchestrator orchestrator,
                       LoopIterationRepository iterationRepo,
                       LoopStageRunRepository stageRepo,
                       CompanyRepository companyRepo,
                       AuditService auditService) {
        this.orchestrator = orchestrator;
        this.iterationRepo = iterationRepo;
        this.stageRepo = stageRepo;
        this.companyRepo = companyRepo;
        this.auditService = auditService;
    }

    @Transactional
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','STRATEGIST')")
    public IterationResponse runCycle(Long companyId) {
        // Confirms the company exists in THIS tenant before launching (findById bypasses the filter).
        TenantGuard.owned(companyRepo.findById(companyId))
                .orElseThrow(() -> ApiException.notFound("Company not found"));

        LoopIteration iteration = orchestrator.startCycle(companyId, TriggerType.MANUAL);
        auditService.record("LOOP_CYCLE_STARTED", "LoopIteration",
                String.valueOf(iteration.getId()), "Manual run for company " + companyId);
        return IterationResponse.of(iteration);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','STRATEGIST','ANALYST','VIEWER')")
    public List<IterationResponse> listIterations(Long companyId) {
        return iterationRepo.findByCompanyIdOrderByIterationNoDesc(companyId).stream()
                .map(IterationResponse::of)
                .toList();
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','STRATEGIST','ANALYST','VIEWER')")
    public IterationDetailResponse getIteration(Long iterationId) {
        LoopIteration it = TenantGuard.owned(iterationRepo.findById(iterationId))
                .orElseThrow(() -> ApiException.notFound("Loop iteration not found"));
        List<StageRunResponse> stages = stageRepo.findByIterationIdOrderByStageNo(it.getId()).stream()
                .map(StageRunResponse::of)
                .toList();
        return new IterationDetailResponse(
                IterationResponse.of(it), it.getInsightsJson(), it.getGapsJson(), stages);
    }
}
