package com.cmo2.customers.controller;

import com.cmo2.customers.dto.CustomerDtos.CanonResponse;
import com.cmo2.customers.dto.CustomerDtos.IdentityResponse;
import com.cmo2.customers.dto.CustomerDtos.ProfileResponse;
import com.cmo2.customers.dto.CustomerDtos.SaveCanonDraftRequest;
import com.cmo2.customers.dto.CustomerDtos.UpdateProfileRequest;
import com.cmo2.customers.service.CustomerCanonService;
import com.cmo2.customers.service.CustomerIdentityService;
import com.cmo2.customers.service.CustomerProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/companies/{companyId}")
@Tag(name = "Customers")
public class CustomerController {

    private final CustomerProfileService profiles;
    private final CustomerIdentityService identity;
    private final CustomerCanonService canons;

    public CustomerController(CustomerProfileService profiles, CustomerIdentityService identity,
                              CustomerCanonService canons) {
        this.profiles = profiles;
        this.identity = identity;
        this.canons = canons;
    }

    @GetMapping("/profile")
    @Operation(summary = "The customer's profile: your notes plus the details GHL reports")
    public ProfileResponse profile(@PathVariable Long companyId) {
        return profiles.get(companyId);
    }

    @PutMapping("/profile")
    @Operation(summary = "Edit the customer's profile (OWNER/ADMIN)")
    public ProfileResponse update(@PathVariable Long companyId, @Valid @RequestBody UpdateProfileRequest request) {
        return profiles.update(companyId, request);
    }

    @GetMapping("/identity")
    @Operation(summary = "Exactly what every AI call is told about this customer")
    public IdentityResponse identity(@PathVariable Long companyId) {
        return identity.preview(companyId);
    }

    @GetMapping("/canon")
    @Operation(summary = "The customer's brand canon: the approved version, the draft and the history")
    public CanonResponse canon(@PathVariable Long companyId) {
        return canons.get(companyId);
    }

    @PutMapping("/canon/draft")
    @Operation(summary = "Write or replace the canon draft (OWNER/ADMIN/STRATEGIST); the AI doesn't see it until approved")
    public CanonResponse saveCanonDraft(@PathVariable Long companyId, @Valid @RequestBody SaveCanonDraftRequest request) {
        return canons.saveDraft(companyId, request);
    }

    @PostMapping("/canon/draft/approve")
    @Operation(summary = "Approve the draft: from now on every AI call for this customer gets it (OWNER/ADMIN)")
    public CanonResponse approveCanonDraft(@PathVariable Long companyId) {
        return canons.approveDraft(companyId);
    }

    @DeleteMapping("/canon/draft")
    @Operation(summary = "Throw the draft away (OWNER/ADMIN/STRATEGIST)")
    public CanonResponse discardCanonDraft(@PathVariable Long companyId) {
        return canons.discardDraft(companyId);
    }
}
