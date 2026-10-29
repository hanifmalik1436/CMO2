package com.cmo2.signup;

import com.cmo2.integrations.gohighlevel.GhlAgencyService;
import com.cmo2.integrations.gohighlevel.GhlAgencyService.ProvisionResult;
import com.cmo2.shared.audit.AuditService;
import com.cmo2.shared.mail.MailService;
import com.cmo2.shared.security.TenantContext;
import com.cmo2.signup.SignupDtos.SignupRequest;
import com.cmo2.signup.SignupDtos.SignupResponse;
import com.cmo2.tenancy.dto.CompanyDtos.CompanyResponse;
import com.cmo2.tenancy.dto.CompanyDtos.CreateCompanyRequest;
import com.cmo2.tenancy.service.CompanyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * The public entry point: "register" no longer creates a CMO2 login. It creates a
 * {@code Company} under CMO2's one fixed agency tenant and provisions a GoHighLevel
 * sub-account for it via {@link GhlAgencyService} — the registrant ends up with access to
 * *that* sub-account, never to the CMO2 dashboard itself.
 *
 * <p>Unauthenticated endpoint calling into {@code @PreAuthorize}-gated services (same
 * problem {@code DemoDataSeeder} solves for its own bootstrap context): bind
 * {@link TenantContext} + a synthetic OWNER {@code SecurityContextHolder} authentication for
 * the duration of this call. Both are ThreadLocals that {@code JwtAuthFilter} unconditionally
 * clears at the end of every request regardless of path, but this method clears them itself
 * too, defensively, rather than relying solely on that.
 */
@Service
public class SignupService {

    private static final Logger log = LoggerFactory.getLogger(SignupService.class);

    private final Long agencyTenantId;
    private final CompanyService companyService;
    private final GhlAgencyService ghlAgencyService;
    private final MailService mailService;
    private final AuditService audit;

    public SignupService(@Value("${cmo2.agency.tenant-id:0}") Long agencyTenantId,
                         CompanyService companyService,
                         GhlAgencyService ghlAgencyService,
                         MailService mailService,
                         AuditService audit) {
        this.agencyTenantId = agencyTenantId;
        this.companyService = companyService;
        this.ghlAgencyService = ghlAgencyService;
        this.mailService = mailService;
        this.audit = audit;
    }

    @Transactional
    public SignupResponse signup(SignupRequest req) {
        if (agencyTenantId == null || agencyTenantId <= 0) {
            throw new IllegalStateException(
                    "cmo2.agency.tenant-id is not configured — create your own agency account once via "
                            + "POST /api/auth/register, then set this to that tenant's id");
        }

        TenantContext.set(agencyTenantId, null);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("public-signup", null,
                        List.of(new SimpleGrantedAuthority("ROLE_OWNER"))));
        try {
            CompanyResponse company = companyService.create(new CreateCompanyRequest(
                    req.businessName().trim(), req.industry(), null, req.website()));

            ProvisionResult result = ghlAgencyService.provisionSubAccount(
                    agencyTenantId, company.id(), req.businessName().trim(),
                    req.contactEmail().trim().toLowerCase(), req.contactName().trim());

            sendWelcomeEmail(req, result);

            audit.recordFor(agencyTenantId, null, "SIGNUP_PROVISIONED", "Company",
                    String.valueOf(company.id()),
                    "GHL sub-account " + result.locationId() + " provisioned for " + req.businessName());

            return new SignupResponse(result.userCreated()
                    ? "Thanks! Check your email for access to your account."
                    : "Thanks! Your account is being set up — we'll follow up shortly with access details.");
        } finally {
            TenantContext.clear();
            SecurityContextHolder.clearContext();
        }
    }

    private void sendWelcomeEmail(SignupRequest req, ProvisionResult result) {
        if (result.userCreated()) {
            mailService.send(req.contactEmail(), "Your account is ready",
                    "Welcome, " + req.contactName() + "!\n\n"
                            + "Your account for " + req.businessName() + " is ready.\n\n"
                            + "Login email: " + req.contactEmail() + "\n"
                            + "Temporary password: " + result.generatedPassword() + "\n\n"
                            + "Please sign in and change your password right away.");
        } else {
            log.warn("GHL sub-account user creation did not succeed for {} ({}) — manual follow-up needed",
                    req.businessName(), req.contactEmail());
            mailService.send(req.contactEmail(), "We're setting up your account",
                    "Welcome, " + req.contactName() + "!\n\n"
                            + "We're finishing setup for " + req.businessName() + " and will follow up "
                            + "shortly with your login details.");
        }
    }
}
