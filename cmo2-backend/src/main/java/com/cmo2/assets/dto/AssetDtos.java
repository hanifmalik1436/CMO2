package com.cmo2.assets.dto;

import com.cmo2.assets.domain.Asset;
import com.cmo2.assets.domain.AssetDeployment;
import com.cmo2.assets.domain.AssetTemplate;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/** Request/response DTOs for the AI Asset Generator. */
public final class AssetDtos {

    private AssetDtos() {}

    public record GenerateRequest(
            @NotBlank String type,                 // AssetType name, e.g. "EMAIL_SEQUENCE"
            @Size(max = 2000) String brief
    ) {}

    /**
     * Publish an approved asset. For a live GHL social post: {@code accountIds} (default: every
     * connected account that hasn't expired), {@code scheduleAt} (default: five minutes from now),
     * optional https {@code mediaUrls}, and {@code confirmRetry} after an unconfirmed attempt.
     */
    public record DeployRequest(
            @NotBlank String platform,
            @Size(max = 20) List<@Size(max = 200) String> accountIds,
            Instant scheduleAt,
            @Size(max = 10) List<@Size(max = 2048) String> mediaUrls,
            boolean confirmRetry
    ) {
        public DeployRequest(String platform) {
            this(platform, null, null, null, false);
        }
    }

    public record EditContentRequest(@NotBlank @Size(max = 20_000) String body) {}

    public record ApproveRequest(@Size(max = 1000) String note) {}

    public record RejectRequest(@NotBlank @Size(max = 1000) String reason) {}

    /** A draft in the approval queue, with its customer's name and its latest publish attempt. */
    public record ApprovalItem(AssetResponse asset, String companyName, DeploymentResponse latestDeployment) {}

    public record SocialAccountResponse(String id, String name, String platform, boolean expired) {}

    /** {@code live}: false for demo connections, whose posts are simulated. */
    public record SocialAccountsResponse(boolean live, List<SocialAccountResponse> accounts, String message) {}

    public record AssetResponse(
            Long id,
            Long companyId,
            String type,
            String language,
            String status,
            String title,
            String contentJson,
            int version,
            Long parentAssetId,
            int tokensUsed,
            Instant createdAt,
            Long reviewedBy,
            Instant reviewedAt,
            String reviewNote
    ) {
        public static AssetResponse of(Asset a) {
            return new AssetResponse(a.getId(), a.getCompanyId(), a.getType().name(), a.getLanguage().name(),
                    a.getStatus().name(), a.getTitle(), a.getContentJson(), a.getVersion(),
                    a.getParentAssetId(), a.getTokensUsed(), a.getCreatedAt(),
                    a.getReviewedBy(), a.getReviewedAt(), a.getReviewNote());
        }
    }

    public record GenerateResponse(AssetResponse english, AssetResponse spanish) {}

    public record DeploymentResponse(
            Long id,
            String platform,
            String status,
            String externalRef,
            String errorMessage,
            Instant deployedAt,
            Instant scheduledFor,
            List<String> targetAccounts
    ) {
        public static DeploymentResponse of(AssetDeployment d) {
            List<String> targets = d.getTargetAccounts() == null || d.getTargetAccounts().isBlank()
                    ? List.of() : List.of(d.getTargetAccounts().split(","));
            return new DeploymentResponse(d.getId(), d.getPlatform().name(), d.getStatus().name(),
                    d.getExternalRef(), d.getErrorMessage(), d.getDeployedAt(), d.getScheduledFor(), targets);
        }
    }

    public record TemplateResponse(Long id, String type, String language, String name) {
        public static TemplateResponse of(AssetTemplate t) {
            return new TemplateResponse(t.getId(), t.getType(), t.getLanguage(), t.getName());
        }
    }
}
