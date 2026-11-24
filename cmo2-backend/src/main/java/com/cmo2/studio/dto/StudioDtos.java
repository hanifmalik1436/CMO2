package com.cmo2.studio.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class StudioDtos {

    private StudioDtos() {}

    /** One format in the catalogue, for the format picker. */
    public record FormatView(String key, String platform, String platformLabel, String label, String assetType,
                             boolean publishable, String ghlPlatform, int maxChars, String guidance) {}

    public record CreateSourceRequest(
            @NotBlank @Size(max = 200) String title,
            @NotBlank @Pattern(regexp = "^(TRANSCRIPT|NOTES|ARTICLE)$", message = "must be TRANSCRIPT, NOTES or ARTICLE") String kind,
            @NotBlank @Size(max = 100_000) String body
    ) {}

    /**
     * A source in the Content Library. {@code status} is PENDING, ANALYZING, READY, TOO_SHORT or FAILED;
     * {@code generated} counts the drafts written from it.
     */
    public record SourceView(Long id, Long companyId, String title, String kind, int characters, String preview,
                             Instant analyzedAt, int ideaCount, Instant createdAt, String status, String statusNote,
                             boolean starred, Instant lastUsedAt, long generated, String originUrl, String originFile) {}

    /** {@code viralityScore} (0–100) rates the hook for this customer's audience; null for older ideas. */
    public record IdeaView(Long id, String type, String title, String summary, List<String> hooks, String quote,
                           Integer viralityScore, String viralityReason) {}

    public record LinkSourceRequest(
            @NotBlank @Size(max = 2048) String url,
            @Size(max = 200) String title
    ) {}

    public record StarRequest(boolean starred) {}

    public record SourceDetail(SourceView source, String body, List<IdeaView> ideas) {}

    /**
     * Generate drafts: each chosen idea (plus the whole source when {@code includeFullSource}) ×
     * each format × {@code draftsPerFormat}.
     */
    public record BatchRequest(
            @NotNull Long sourceId,
            boolean includeFullSource,
            @Size(max = 20) List<Long> ideaIds,
            @NotEmpty @Size(max = 20) List<String> formats,
            @Min(1) @Max(3) int draftsPerFormat,
            boolean translate,
            boolean smartSchedule
    ) {
        public BatchRequest(Long sourceId, boolean includeFullSource, List<Long> ideaIds, List<String> formats,
                            int draftsPerFormat, boolean translate) {
            this(sourceId, includeFullSource, ideaIds, formats, draftsPerFormat, translate, false);
        }
    }

    public record BatchView(Long id, Long companyId, Long sourceId, String status, int totalPieces, int completed,
                            int failed, String note, Instant createdAt, Instant finishedAt) {}
}
