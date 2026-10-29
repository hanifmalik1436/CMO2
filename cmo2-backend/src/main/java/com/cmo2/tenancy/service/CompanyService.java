package com.cmo2.tenancy.service;

import com.cmo2.shared.billing.PlanLimits;
import com.cmo2.shared.audit.AuditService;
import com.cmo2.shared.exceptions.ApiException;
import com.cmo2.integrations.core.Timezones;
import com.cmo2.tenancy.events.CompanyTimezoneChangedEvent;
import org.springframework.context.ApplicationEventPublisher;
import com.cmo2.shared.tenancy.TenantGuard;
import com.cmo2.tenancy.domain.Company;
import com.cmo2.tenancy.domain.CompanyStatus;
import com.cmo2.tenancy.dto.CompanyDtos.CompanyResponse;
import com.cmo2.tenancy.dto.CompanyDtos.CreateCompanyRequest;
import com.cmo2.tenancy.dto.CompanyDtos.UpdateCompanyRequest;
import com.cmo2.tenancy.repository.CompanyRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Company CRUD for the Multi-Company module. Every read/write is implicitly
 * tenant-scoped by the Hibernate filter, so there is no way to touch another
 * tenant's company even with a guessed id.
 *
 * <p>Authorization is enforced HERE (service layer) via {@code @PreAuthorize},
 * per the non-negotiable rule — the UI's role checks are convenience only.
 */
@Service
public class CompanyService {

    private final CompanyRepository companyRepository;
    private final AuditService auditService;
    private final PlanLimits planLimits;
    private final ApplicationEventPublisher events;

    public CompanyService(CompanyRepository companyRepository, AuditService auditService,
                          PlanLimits planLimits, ApplicationEventPublisher events) {
        this.companyRepository = companyRepository;
        this.auditService = auditService;
        this.planLimits = planLimits;
        this.events = events;
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','STRATEGIST','ANALYST','VIEWER')")
    public List<CompanyResponse> list() {
        return companyRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(CompanyService::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','STRATEGIST','ANALYST','VIEWER')")
    public CompanyResponse get(Long id) {
        return toResponse(findOwned(id));
    }

    @Transactional
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    public CompanyResponse create(CreateCompanyRequest req) {
        planLimits.assertCanAddCompany();
        if (companyRepository.existsByName(req.name().trim())) {
            throw ApiException.conflict("A company named '" + req.name().trim() + "' already exists");
        }
        Company company = new Company();
        company.setName(req.name().trim());
        company.setIndustry(req.industry());
        company.setBrandVoice(req.brandVoice());
        company.setWebsite(req.website());
        company.setStatus(CompanyStatus.ACTIVE);
        company = companyRepository.save(company);

        auditService.record("COMPANY_CREATED", "Company", String.valueOf(company.getId()), company.getName());
        return toResponse(company);
    }

    @Transactional
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    public CompanyResponse update(Long id, UpdateCompanyRequest req) {
        Company company = findOwned(id);
        company.setName(req.name().trim());
        company.setIndustry(req.industry());
        company.setBrandVoice(req.brandVoice());
        company.setWebsite(req.website());
        company = companyRepository.save(company);

        auditService.record("COMPANY_UPDATED", "Company", String.valueOf(company.getId()), company.getName());
        return toResponse(company);
    }

    @Transactional
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    public void archive(Long id) {
        Company company = findOwned(id);
        company.setStatus(CompanyStatus.ARCHIVED);
        companyRepository.save(company);
        auditService.record("COMPANY_ARCHIVED", "Company", String.valueOf(company.getId()), company.getName());
    }

    /**
     * Links a customer to its GHL sub-account. Flushes immediately so a sub-account already
     * linked elsewhere (in any tenant) surfaces as a clean 409 rather than at commit time.
     */
    @Transactional
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    public CompanyResponse linkGhlLocation(Long id, String locationId) {
        Company company = findOwned(id);
        company.setGhlLocationId(locationId);
        try {
            company = companyRepository.saveAndFlush(company);
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("That GHL sub-account is already linked to another customer");
        }
        auditService.record("COMPANY_GHL_LINKED", "Company", String.valueOf(company.getId()), locationId);
        return toResponse(company);
    }

    /** Sets the customer's timezone (an IANA id such as "Europe/London"; blank clears it). */
    @Transactional
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    public CompanyResponse setTimezone(Long id, String timezone) {
        Company company = findOwned(id);
        String zone = validTimezone(timezone);
        boolean changed = !java.util.Objects.equals(zone, company.getTimezone());
        company.setTimezone(zone);
        company = companyRepository.save(company);
        if (changed) {
            events.publishEvent(new CompanyTimezoneChangedEvent(company.getTenantId(), company.getId(), zone));
        }
        return toResponse(company);
    }

    /**
     * Internal (sync listener, no principal): copies the sub-account's timezone, and its website
     * when the owner hasn't set one. The tenant is named explicitly because the Hibernate filter
     * isn't active on background threads.
     */
    @Transactional
    public void applyPlatformDetails(Long tenantId, Long companyId, String timezone, String website) {
        Company company = companyRepository.findById(companyId)
                .filter(c -> tenantId.equals(c.getTenantId()))
                .orElse(null);
        if (company == null) {
            return;
        }
        String zone = timezone == null ? null : Timezones.parse(timezone) == null ? null : timezone.trim();
        if (zone != null && !zone.equals(company.getTimezone())) {
            company.setTimezone(zone);
            events.publishEvent(new CompanyTimezoneChangedEvent(tenantId, companyId, zone));
        }
        if ((company.getWebsite() == null || company.getWebsite().isBlank()) && website != null && !website.isBlank()) {
            company.setWebsite(website.trim().length() > 255 ? website.trim().substring(0, 255) : website.trim());
        }
        companyRepository.save(company);
    }

    private static String validTimezone(String timezone) {
        if (timezone == null || timezone.isBlank()) {
            return null;
        }
        if (Timezones.parse(timezone) == null) {
            throw ApiException.badRequest("Unknown timezone: use an IANA id such as Europe/London");
        }
        return timezone.trim();
    }

    private Company findOwned(Long id) {
        // findById bypasses the Hibernate filter, so guard ownership explicitly.
        return TenantGuard.owned(companyRepository.findById(id))
                .orElseThrow(() -> ApiException.notFound("Company not found"));
    }

    private static CompanyResponse toResponse(Company c) {
        return new CompanyResponse(
                c.getId(), c.getName(), c.getIndustry(), c.getBrandVoice(), c.getWebsite(), c.getTimezone(),
                c.getGhlLocationId(), c.getStatus(), c.getOnboardingStep(), c.getCreatedAt());
    }
}
