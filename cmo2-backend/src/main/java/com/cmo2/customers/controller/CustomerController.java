package com.cmo2.customers.controller;

import com.cmo2.customers.dto.CustomerDtos.IdentityResponse;
import com.cmo2.customers.dto.CustomerDtos.ProfileResponse;
import com.cmo2.customers.dto.CustomerDtos.UpdateProfileRequest;
import com.cmo2.customers.service.CustomerIdentityService;
import com.cmo2.customers.service.CustomerProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

    public CustomerController(CustomerProfileService profiles, CustomerIdentityService identity) {
        this.profiles = profiles;
        this.identity = identity;
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
        return new IdentityResponse(companyId, identity.preview(companyId));
    }
}
