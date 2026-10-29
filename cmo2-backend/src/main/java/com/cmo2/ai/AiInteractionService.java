package com.cmo2.ai;

import com.cmo2.ai.domain.AiInteraction;
import com.cmo2.ai.repository.AiInteractionRepository;
import com.cmo2.shared.exceptions.ApiException;
import com.cmo2.shared.tenancy.TenantGuard;
import com.cmo2.tenancy.repository.CompanyRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/** Reads the AI call log: for the owner's customer screen, and for each cycle's record. */
@Service
public class AiInteractionService {

    private static final int MAX_LIST = 100;

    private final AiInteractionRepository repository;
    private final CompanyRepository companies;

    public AiInteractionService(AiInteractionRepository repository, CompanyRepository companies) {
        this.repository = repository;
        this.companies = companies;
    }

    public record Interaction(Long id, Long iterationId, String stage, String kind, String tier, String model,
                              String prompt, String response, long inputTokens, long outputTokens, Instant createdAt) {
        static Interaction of(AiInteraction a) {
            return new Interaction(a.getId(), a.getIterationId(), a.getStage(), a.getKind(), a.getTier(), a.getModel(),
                    a.getPromptText(), a.getResponseText(), a.getInputTokens(), a.getOutputTokens(), a.getCreatedAt());
        }
    }

    /** The customer's latest AI calls, newest first. Prompts can hold lead details, so owner/admin only. */
    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    public List<Interaction> recent(Long companyId, int limit) {
        var company = TenantGuard.owned(companies.findById(companyId))
                .orElseThrow(() -> ApiException.notFound("Company not found"));
        return repository.findByTenantIdAndCompanyIdOrderByCreatedAtDesc(company.getTenantId(), companyId,
                        PageRequest.of(0, Math.min(Math.max(limit, 1), MAX_LIST)))
                .stream().map(Interaction::of).toList();
    }

    /** Internal (loop threads): the customer's calls in [from, to), oldest first. */
    @Transactional(readOnly = true)
    public List<Interaction> between(Long tenantId, Long companyId, Instant from, Instant to) {
        return repository.findByTenantIdAndCompanyIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAsc(
                tenantId, companyId, from, to).stream().map(Interaction::of).toList();
    }
}
