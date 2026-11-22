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

    /**
     * What the AI is told about the customer: {@code text} goes first in every call's system prompt
     * (and is cached); {@code figures} goes in the request only where the task uses this week's figures.
     */
    public record IdentityResponse(Long companyId, String text, String figures) {}

    public record CanonVersionResponse(Long id, Integer versionNo, String status, String content, String changeNote,
                                       Long proposedByUserId, Long approvedByUserId, Instant approvedAt,
                                       Instant createdAt, Instant updatedAt) {}

    /** The approved version the AI gets, the working draft if any, and every version, newest first. */
    public record CanonResponse(Long companyId, CanonVersionResponse approved, CanonVersionResponse draft,
                                List<CanonVersionResponse> history) {}

    public record SaveCanonDraftRequest(
            @NotBlank @Size(max = 20_000) String content,
            @Size(max = 500) String changeNote
    ) {}
}
