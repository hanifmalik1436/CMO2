package com.cmo2.docs.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Map;

/** Request/response DTOs for workspace tools. Secrets are accepted, never returned. */
public final class DocsDtos {

    private DocsDtos() {}

    /**
     * Settings for one tool. {@code secret} is the API token (or Slack webhook URL); leave it blank
     * when editing to keep the stored one. Only the fields for the chosen tool are read.
     */
    public record SaveDocConnectionRequest(
            @Size(max = 4096) String secret,
            @Size(max = 512) String notionRootId,
            @Size(max = 255) String confluenceSiteUrl,
            @Email @Size(max = 255) String confluenceEmail,
            @Size(max = 255) String confluenceSpaceKey,
            @Size(max = 64) String confluenceParentPageId,
            @Size(max = 64) String linearTeamId
    ) {}

    public record DocConnectionResponse(
            String provider,
            String name,
            boolean configured,
            String maskedSecret,
            Map<String, String> settings,
            String status,
            String lastError,
            Instant lastCheckedAt
    ) {}

    public record DocTargetsResponse(
            Long companyId,
            String notionPageId,
            String notionUrl,
            String confluencePageId,
            String confluenceUrl,
            String linearProjectId,
            String linearUrl,
            String lastError
    ) {}

    /** Point a customer at existing pages/projects instead of the ones CMO² created. Blank clears. */
    public record UpdateDocTargetsRequest(
            @Size(max = 512) String notionPageId,
            @Size(max = 64) String confluencePageId,
            @Size(max = 64) String linearProjectId
    ) {}
}
