package com.cmo2.signup;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request/response DTOs for the public signup flow (provisions a GHL sub-account; no CMO2 login issued). */
public final class SignupDtos {

    private SignupDtos() {}

    public record SignupRequest(
            @NotBlank @Size(max = 160) String businessName,
            @NotBlank @Size(max = 160) String contactName,
            @NotBlank @Email @Size(max = 190) String contactEmail,
            @Size(max = 120) String industry,
            @Size(max = 255) String website
    ) {}

    public record SignupResponse(String message) {}
}
