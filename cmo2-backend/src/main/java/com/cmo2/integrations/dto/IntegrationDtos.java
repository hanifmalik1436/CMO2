package com.cmo2.integrations.dto;

import com.cmo2.integrations.domain.PlatformConnection;
import com.cmo2.integrations.domain.SyncJob;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/** Request/response DTOs for the Integrations module. Secrets are never returned. */
public final class IntegrationDtos {

    private IntegrationDtos() {}

    public record ConnectRequest(
            @NotBlank String platform,            // e.g. "GOHIGHLEVEL"
            @NotBlank @Size(max = 4096) String apiKey,
            @Size(max = 128) String locationId
    ) {}

    public record ReconnectRequest(
            @NotBlank @Size(max = 4096) String apiKey,
            @Size(max = 128) String locationId
    ) {}

    public record ConnectionResponse(
            Long id,
            Long companyId,
            String platform,
            String platformName,
            String maskedKey,
            String locationId,
            String status,
            String health,
            Instant lastSyncAt,
            String lastError
    ) {
        public static ConnectionResponse of(PlatformConnection c) {
            String masked = "GHL_AGENCY".equals(c.getAuthMode())
                    ? "via agency"
                    : "••••" + (c.getApiKeyLast4() == null ? "" : c.getApiKeyLast4());
            return new ConnectionResponse(
                    c.getId(), c.getCompanyId(), c.getPlatform().name(), c.getPlatform().displayName,
                    masked, c.getLocationId(), c.getStatus().name(), c.getHealth().name(),
                    c.getLastSyncAt(), c.getLastError());
        }
    }

    public record SyncJobResponse(
            Long id,
            String type,
            String status,
            int kpisIngested,
            Instant startedAt,
            Instant finishedAt,
            String errorMessage
    ) {
        public static SyncJobResponse of(SyncJob j) {
            return new SyncJobResponse(j.getId(), j.getType().name(), j.getStatus().name(),
                    j.getKpisIngested(), j.getStartedAt(), j.getFinishedAt(), j.getErrorMessage());
        }
    }

    public record SyncTriggerResponse(Long connectionId, String health, int kpisIngested, String message) {}
}
