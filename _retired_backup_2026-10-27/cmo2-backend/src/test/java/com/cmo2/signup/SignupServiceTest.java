package com.cmo2.signup;

import com.cmo2.integrations.gohighlevel.GhlAgencyService;
import com.cmo2.integrations.gohighlevel.GhlAgencyService.ProvisionResult;
import com.cmo2.shared.audit.AuditService;
import com.cmo2.shared.mail.MailService;
import com.cmo2.signup.SignupDtos.SignupRequest;
import com.cmo2.tenancy.dto.CompanyDtos.CompanyResponse;
import com.cmo2.tenancy.domain.CompanyStatus;
import com.cmo2.tenancy.service.CompanyService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests, not a {@code @SpringBootTest}: {@code SignupService} binds to "the one fixed
 * agency tenant" via a boot-time {@code @Value}, which a running test can't retroactively
 * point at a tenant it creates mid-test. Mocking the collaborators tests the orchestration
 * logic directly and faster, which is what actually matters here — the collaborators
 * ({@code CompanyService}, {@code GhlAgencyService}) have their own coverage elsewhere.
 */
class SignupServiceTest {

    private final CompanyService companyService = mock(CompanyService.class);
    private final GhlAgencyService ghlAgencyService = mock(GhlAgencyService.class);
    private final MailService mailService = mock(MailService.class);
    private final AuditService audit = mock(AuditService.class);

    private SignupRequest request() {
        return new SignupRequest("Acme Dental", "Jane Doe", "jane@acme-dental.example", "Healthcare", "https://acme.example");
    }

    private CompanyResponse fakeCompany() {
        return new CompanyResponse(42L, "Acme Dental", "Healthcare", null, "https://acme.example",
                CompanyStatus.ACTIVE, 0, Instant.now());
    }

    @Test
    void misconfiguredAgencyTenantFailsFastWithoutTouchingAnyCollaborator() {
        SignupService service = new SignupService(0L, companyService, ghlAgencyService, mailService, audit);

        assertThatThrownBy(() -> service.signup(request()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cmo2.agency.tenant-id");

        verify(companyService, never()).create(any());
        verify(ghlAgencyService, never()).provisionSubAccount(anyLong(), anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void happyPathCreatesCompanyProvisionsGhlAndEmailsCredentials() {
        long agencyTenantId = 7L;
        SignupService service = new SignupService(agencyTenantId, companyService, ghlAgencyService, mailService, audit);

        when(companyService.create(any())).thenReturn(fakeCompany());
        when(ghlAgencyService.provisionSubAccount(eq(agencyTenantId), eq(42L), eq("Acme Dental"),
                eq("jane@acme-dental.example"), eq("Jane Doe")))
                .thenReturn(new ProvisionResult("loc_123", true, "generatedPw1!"));

        var response = service.signup(request());

        assertThat(response.message()).containsIgnoringCase("check your email");

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(mailService).send(eq("jane@acme-dental.example"), anyString(), bodyCaptor.capture());
        assertThat(bodyCaptor.getValue()).contains("generatedPw1!");

        verify(audit).recordFor(eq(agencyTenantId), eq(null), eq("SIGNUP_PROVISIONED"),
                eq("Company"), eq("42"), anyString());
    }

    @Test
    void ghlUserCreationFailureStillCompletesSignupWithAFollowUpMessage() {
        long agencyTenantId = 7L;
        SignupService service = new SignupService(agencyTenantId, companyService, ghlAgencyService, mailService, audit);

        when(companyService.create(any())).thenReturn(fakeCompany());
        when(ghlAgencyService.provisionSubAccount(anyLong(), anyLong(), anyString(), anyString(), anyString()))
                .thenReturn(new ProvisionResult("loc_123", false, null));

        var response = service.signup(request());

        assertThat(response.message()).containsIgnoringCase("follow up");
        verify(mailService).send(eq("jane@acme-dental.example"), anyString(),
                org.mockito.ArgumentMatchers.argThat(body -> !body.contains("Temporary password")));
    }
}
