package com.cmo2.strategists.service;

import com.cmo2.shared.audit.AuditService;
import com.cmo2.shared.exceptions.ApiException;
import com.cmo2.shared.security.TenantContext;
import com.cmo2.shared.tenancy.TenantGuard;
import com.cmo2.strategists.domain.GateStatus;
import com.cmo2.strategists.domain.ReviewGate;
import com.cmo2.strategists.domain.SessionStatus;
import com.cmo2.strategists.domain.StrategistSession;
import com.cmo2.strategists.dto.StrategistDtos.GateResponse;
import com.cmo2.strategists.repository.ReviewGateRepository;
import com.cmo2.strategists.repository.StrategistSessionRepository;
import com.cmo2.tenancy.repository.CompanyRepository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Review gates RG-1 … RG-4 — the human approval checkpoints between strategist phases.
 * Approving a gate (role-restricted) flips that phase's completed sessions to APPROVED,
 * which releases their deliverables into the company workspace for Strange Loop Stage 1.
 */
@Service
public class ReviewGateService {

    private static final int PHASES = 4;

    private final ReviewGateRepository gateRepo;
    private final StrategistSessionRepository sessionRepo;
    private final CompanyRepository companyRepo;
    private final AgentRegistry agents;
    private final AuditService audit;

    public ReviewGateService(ReviewGateRepository gateRepo, StrategistSessionRepository sessionRepo,
                             CompanyRepository companyRepo, AgentRegistry agents, AuditService audit) {
        this.gateRepo = gateRepo;
        this.sessionRepo = sessionRepo;
        this.companyRepo = companyRepo;
        this.agents = agents;
        this.audit = audit;
    }

    @Transactional
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','STRATEGIST','ANALYST','VIEWER')")
    public List<GateResponse> list(Long companyId) {
        TenantGuard.owned(companyRepo.findById(companyId))
                .orElseThrow(() -> ApiException.notFound("Company not found"));
        for (int phase = 1; phase <= PHASES; phase++) {
            ensureGate(companyId, phase);
        }
        return gateRepo.findByCompanyIdOrderByGateCodeAsc(companyId).stream()
                .map(GateResponse::of)
                .toList();
    }

    @Transactional
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    public GateResponse approve(Long companyId, String gateCode, String notes) {
        TenantGuard.owned(companyRepo.findById(companyId))
                .orElseThrow(() -> ApiException.notFound("Company not found"));
        int phase = phaseOf(gateCode);
        ReviewGate gate = ensureGate(companyId, phase);

        gate.setStatus(GateStatus.APPROVED);
        gate.setApprovedBy(TenantContext.getUserId());
        gate.setApprovedAt(Instant.now());
        gate.setNotes(notes);
        gateRepo.save(gate);

        // Release this phase's completed deliverables into the workspace.
        int released = 0;
        for (StrategistSession session : sessionRepo.findByCompanyIdAndStatus(companyId, SessionStatus.COMPLETED)) {
            if (agents.requireByCode(session.getAgentCode()).getPhase() == phase) {
                session.setStatus(SessionStatus.APPROVED);
                sessionRepo.save(session);
                released++;
            }
        }

        audit.record("REVIEW_GATE_APPROVED", "ReviewGate", gateCode,
                "Company " + companyId + " — released " + released + " session(s)");
        return GateResponse.of(gate);
    }

    private ReviewGate ensureGate(Long companyId, int phase) {
        String code = "RG-" + phase;
        return gateRepo.findFirstByCompanyIdAndGateCode(companyId, code).orElseGet(() -> {
            ReviewGate g = new ReviewGate();
            g.setCompanyId(companyId);
            g.setGateCode(code);
            g.setPhase(phase);
            g.setStatus(GateStatus.PENDING);
            return gateRepo.save(g);
        });
    }

    private int phaseOf(String gateCode) {
        if (gateCode == null || !gateCode.startsWith("RG-")) {
            throw ApiException.badRequest("Invalid gate code: " + gateCode);
        }
        try {
            int phase = Integer.parseInt(gateCode.substring(3));
            if (phase < 1 || phase > PHASES) throw new NumberFormatException();
            return phase;
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("Invalid gate code: " + gateCode);
        }
    }
}
