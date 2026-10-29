package com.cmo2.customers.service;

import com.cmo2.customers.domain.CustomerProfile;
import com.cmo2.customers.dto.CustomerDtos.ProfileResponse;
import com.cmo2.customers.dto.CustomerDtos.UpdateProfileRequest;
import com.cmo2.customers.repository.CustomerProfileRepository;
import com.cmo2.integrations.core.SyncModels.CustomerFacts;
import com.cmo2.shared.audit.AuditService;
import com.cmo2.shared.exceptions.ApiException;
import com.cmo2.shared.tenancy.TenantGuard;
import com.cmo2.tenancy.domain.Company;
import com.cmo2.tenancy.dto.CompanyDtos.UpdateCompanyRequest;
import com.cmo2.tenancy.repository.CompanyRepository;
import com.cmo2.tenancy.service.CompanyService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * A customer's profile: the owner's notes on the business plus the details GHL reports about it.
 * Company fields (name, industry, voice, website, timezone) stay in the tenancy module and are
 * changed through {@link CompanyService}.
 */
@Service
public class CustomerProfileService {

    private final CustomerProfileRepository profiles;
    private final CompanyRepository companies;
    private final CompanyService companyService;
    private final AuditService audit;

    public CustomerProfileService(CustomerProfileRepository profiles, CompanyRepository companies,
                                  CompanyService companyService, AuditService audit) {
        this.profiles = profiles;
        this.companies = companies;
        this.companyService = companyService;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','STRATEGIST','ANALYST','VIEWER')")
    public ProfileResponse get(Long companyId) {
        Company company = owned(companyId);
        return toResponse(company, profiles.findByTenantIdAndCompanyId(company.getTenantId(), companyId).orElse(null));
    }

    @Transactional
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    public ProfileResponse update(Long companyId, UpdateProfileRequest req) {
        owned(companyId);
        companyService.update(companyId, new UpdateCompanyRequest(req.name(), blankToNull(req.industry()),
                blankToNull(req.brandVoice()), blankToNull(req.website())));
        companyService.setTimezone(companyId, req.timezone());

        Company company = owned(companyId);
        CustomerProfile p = findOrNew(company.getTenantId(), companyId);
        p.setOffer(blankToNull(req.offer()));
        p.setAudience(blankToNull(req.audience()));
        p.setBannedTopics(blankToNull(req.bannedTopics()));
        p.setNotes(blankToNull(req.notes()));
        p = profiles.save(p);

        audit.record("CUSTOMER_PROFILE_UPDATED", "Company", String.valueOf(companyId), company.getName());
        return toResponse(company, p);
    }

    /**
     * Internal (sync listener, no principal): stores what GHL just reported about the business.
     * Fields GHL left empty keep their previous value. Runs in its own transaction: it is called
     * after the sync has committed, when the sync's transaction can no longer be joined.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void applyFacts(Long tenantId, Long companyId, CustomerFacts facts) {
        if (companies.findById(companyId).filter(c -> tenantId.equals(c.getTenantId())).isEmpty()) {
            return;
        }
        companyService.applyPlatformDetails(tenantId, companyId, facts.timezone(), facts.website());

        CustomerProfile p = findOrNew(tenantId, companyId);
        p.setGhlBusinessName(keep(p.getGhlBusinessName(), facts.businessName(), 160));
        p.setCity(keep(p.getCity(), facts.city(), 120));
        p.setCountry(keep(p.getCountry(), facts.country(), 80));
        p.setPhone(keep(p.getPhone(), facts.phone(), 40));
        p.setEmail(keep(p.getEmail(), facts.email(), 160));
        if (facts.socialChannels() != null) {
            p.setSocialChannels(clip(String.join(",", facts.socialChannels()), 500));
        }
        p.setFactsSyncedAt(Instant.now());
        profiles.save(p);
    }

    private CustomerProfile findOrNew(Long tenantId, Long companyId) {
        return profiles.findByTenantIdAndCompanyId(tenantId, companyId).orElseGet(() -> {
            CustomerProfile p = new CustomerProfile();
            p.setTenantId(tenantId);
            p.setCompanyId(companyId);
            return p;
        });
    }

    private Company owned(Long companyId) {
        return TenantGuard.owned(companies.findById(companyId))
                .orElseThrow(() -> ApiException.notFound("Company not found"));
    }

    static List<String> channels(CustomerProfile p) {
        if (p == null || p.getSocialChannels() == null || p.getSocialChannels().isBlank()) {
            return List.of();
        }
        return Arrays.stream(p.getSocialChannels().split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private static ProfileResponse toResponse(Company c, CustomerProfile p) {
        return new ProfileResponse(c.getId(), c.getName(), c.getIndustry(), c.getBrandVoice(), c.getWebsite(),
                c.getTimezone(), c.getGhlLocationId(),
                p == null ? null : p.getOffer(),
                p == null ? null : p.getAudience(),
                p == null ? null : p.getBannedTopics(),
                p == null ? null : p.getNotes(),
                p == null ? null : p.getGhlBusinessName(),
                p == null ? null : p.getCity(),
                p == null ? null : p.getCountry(),
                p == null ? null : p.getPhone(),
                p == null ? null : p.getEmail(),
                channels(p),
                p == null ? null : p.getFactsSyncedAt());
    }

    private static String keep(String current, String incoming, int max) {
        return incoming == null || incoming.isBlank() ? current : clip(incoming.trim(), max);
    }

    private static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
