package com.cmo2.customers.service;

import com.cmo2.customers.domain.CanonStatus;
import com.cmo2.customers.domain.CustomerCanon;
import com.cmo2.customers.dto.CustomerDtos.CanonResponse;
import com.cmo2.customers.dto.CustomerDtos.CanonVersionResponse;
import com.cmo2.customers.dto.CustomerDtos.SaveCanonDraftRequest;
import com.cmo2.customers.repository.CustomerCanonRepository;
import com.cmo2.shared.audit.AuditService;
import com.cmo2.shared.exceptions.ApiException;
import com.cmo2.shared.security.TenantContext;
import com.cmo2.shared.tenancy.TenantGuard;
import com.cmo2.tenancy.domain.Company;
import com.cmo2.tenancy.repository.CompanyRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * A customer's brand canon (drift-control plan, step 1). Staff draft it, the owner or an admin
 * approves it, and only the approved version reaches the AI. Approving retires the previous
 * version; every version is kept so the owner can see, and go back to, what the AI was told.
 */
@Service
public class CustomerCanonService {

    static final int MAX_HISTORY = 50;

    private final CustomerCanonRepository canons;
    private final CompanyRepository companies;
    private final AuditService audit;

    public CustomerCanonService(CustomerCanonRepository canons, CompanyRepository companies, AuditService audit) {
        this.canons = canons;
        this.companies = companies;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','STRATEGIST','ANALYST')")
    public CanonResponse get(Long companyId) {
        return view(owned(companyId));
    }

    /** Creates the customer's draft, or replaces the text of the one already open. */
    @Transactional
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','STRATEGIST')")
    public CanonResponse saveDraft(Long companyId, SaveCanonDraftRequest req) {
        Company company = owned(companyId);
        CustomerCanon draft = find(company, CanonStatus.DRAFT).orElseGet(() -> {
            CustomerCanon c = new CustomerCanon();
            c.setCompanyId(companyId);
            c.setStatus(CanonStatus.DRAFT);
            return c;
        });
        draft.setContent(req.content().strip());
        draft.setChangeNote(blankToNull(req.changeNote()));
        draft.setProposedByUserId(TenantContext.getUserId());
        canons.save(draft);
        audit.record("CUSTOMER_CANON_DRAFTED", "Company", String.valueOf(companyId), company.getName());
        return view(company);
    }

    /** The draft becomes the version every AI call for this customer gets; the previous one is retired. */
    @Transactional
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    public CanonResponse approveDraft(Long companyId) {
        Company company = owned(companyId);
        CustomerCanon draft = find(company, CanonStatus.DRAFT)
                .orElseThrow(() -> ApiException.badRequest("There is no draft to approve"));
        find(company, CanonStatus.APPROVED).ifPresent(previous -> {
            previous.setStatus(CanonStatus.RETIRED);
            canons.saveAndFlush(previous);
        });
        int versionNo = canons.maxVersionNo(company.getTenantId(), companyId) + 1;
        draft.setVersionNo(versionNo);
        draft.setStatus(CanonStatus.APPROVED);
        draft.setApprovedByUserId(TenantContext.getUserId());
        draft.setApprovedAt(Instant.now());
        canons.save(draft);
        audit.record("CUSTOMER_CANON_APPROVED", "Company", String.valueOf(companyId),
                company.getName() + " v" + versionNo);
        return view(company);
    }

    @Transactional
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','STRATEGIST')")
    public CanonResponse discardDraft(Long companyId) {
        Company company = owned(companyId);
        find(company, CanonStatus.DRAFT).ifPresent(draft -> {
            canons.delete(draft);
            audit.record("CUSTOMER_CANON_DRAFT_DISCARDED", "Company", String.valueOf(companyId), company.getName());
        });
        return view(company);
    }

    /** Internal (loop threads): the approved canon for the customer, if one has been approved. */
    @Transactional(readOnly = true)
    public Optional<CustomerCanon> approved(Long tenantId, Long companyId) {
        return canons.findFirstByTenantIdAndCompanyIdAndStatusOrderByIdDesc(tenantId, companyId, CanonStatus.APPROVED);
    }

    private Optional<CustomerCanon> find(Company company, CanonStatus status) {
        return canons.findFirstByTenantIdAndCompanyIdAndStatusOrderByIdDesc(company.getTenantId(), company.getId(), status);
    }

    private CanonResponse view(Company company) {
        return new CanonResponse(company.getId(),
                find(company, CanonStatus.APPROVED).map(CustomerCanonService::toResponse).orElse(null),
                find(company, CanonStatus.DRAFT).map(CustomerCanonService::toResponse).orElse(null),
                canons.findByTenantIdAndCompanyIdOrderByIdDesc(company.getTenantId(), company.getId(),
                        PageRequest.of(0, MAX_HISTORY)).stream().map(CustomerCanonService::toResponse).toList());
    }

    private Company owned(Long companyId) {
        return TenantGuard.owned(companies.findById(companyId))
                .orElseThrow(() -> ApiException.notFound("Company not found"));
    }

    static CanonVersionResponse toResponse(CustomerCanon c) {
        return new CanonVersionResponse(c.getId(), c.getVersionNo(), c.getStatus().name(), c.getContent(),
                c.getChangeNote(), c.getProposedByUserId(), c.getApprovedByUserId(), c.getApprovedAt(),
                c.getCreatedAt(), c.getUpdatedAt());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
