package com.cmo2.customers.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class CustomerDtos {

    private CustomerDtos() {}

    public record ProfileResponse(
            Long companyId,
            String name,
            String industry,
            String brandVoice,
            String website,
            String timezone,
            String ghlLocationId,
            String offer,
            String audience,
            String bannedTopics,
            String notes,
            String ghlBusinessName,
            String city,
            String country,
            String phone,
            String email,
            List<String> socialChannels,
            Instant factsSyncedAt
    ) {}

    /** Everything the owner can edit about a customer. Blank optional fields clear them. */
    public record UpdateProfileRequest(
            @NotBlank @Size(max = 160) String name,
            @Size(max = 120) String industry,
            @Size(max = 255) String brandVoice,
            @Size(max = 255) String website,
            @Size(max = 64) String timezone,
            @Size(max = 1000) String offer,
            @Size(max = 1000) String audience,
            @Size(max = 1000) String bannedTopics,
            @Size(max = 8000) String notes
    ) {}

    public record IdentityResponse(Long companyId, String text) {}
}
