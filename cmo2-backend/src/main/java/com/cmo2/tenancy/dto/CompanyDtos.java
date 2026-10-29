package com.cmo2.tenancy.dto;

import com.cmo2.tenancy.domain.CompanyStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/** Request/response DTOs for the Multi-Company module. */
public final class CompanyDtos {

    private CompanyDtos() {}

    public record CreateCompanyRequest(
            @NotBlank @Size(max = 160) String name,
            @Size(max = 120) String industry,
            @Size(max = 255) String brandVoice,
            @Size(max = 255) String website
    ) {}

    public record UpdateCompanyRequest(
            @NotBlank @Size(max = 160) String name,
            @Size(max = 120) String industry,
            @Size(max = 255) String brandVoice,
            @Size(max = 255) String website
    ) {}

    public record CompanyResponse(
            Long id,
            String name,
            String industry,
            String brandVoice,
            String website,
            String timezone,
            String ghlLocationId,
            CompanyStatus status,
            int onboardingStep,
            Instant createdAt
    ) {}
}
