package com.cmo2.strangeloop.dto;

import com.cmo2.strangeloop.domain.LoopIteration;
import com.cmo2.strangeloop.domain.LoopStageRun;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/** Request and response DTOs for the Strange Loop module. */
public final class LoopDtos {

    private LoopDtos() {}

    public record IterationResponse(
            Long id,
            Long companyId,
            int iterationNo,
            String status,
            String triggerType,
            Instant startedAt,
            Instant completedAt
    ) {
        public static IterationResponse of(LoopIteration it) {
            return new IterationResponse(it.getId(), it.getCompanyId(), it.getIterationNo(),
                    it.getStatus().name(), it.getTriggerType().name(), it.getStartedAt(), it.getCompletedAt());
        }
    }

    public record StageRunResponse(
            int stageNo,
            String stageName,
            String status,
            int progressPct,
            String dataJson,
            Instant startedAt,
            Instant completedAt
    ) {
        public static StageRunResponse of(LoopStageRun s) {
            return new StageRunResponse(s.getStageNo(), s.getStageName().name(), s.getStatus().name(),
                    s.getProgressPct(), s.getDataJson(), s.getStartedAt(), s.getCompletedAt());
        }
    }

    public record IterationDetailResponse(
            IterationResponse iteration,
            String insightsJson,
            String gapsJson,
            List<StageRunResponse> stages
    ) {}

    /**
     * A customer's cadence. {@code cadence} is OFF, DAILY, WEEKLY or MONTHLY; {@code timeOfDay} is
     * HH:mm in the customer's timezone; WEEKLY uses {@code dayOfWeek} (1 = Monday) and MONTHLY
     * {@code dayOfMonth} (1–28, so every month has it).
     */
    public record ScheduleRequest(
            @NotBlank
            @Pattern(regexp = "^(OFF|DAILY|WEEKLY|MONTHLY)$", message = "must be OFF, DAILY, WEEKLY or MONTHLY")
            String cadence,
            @Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$", message = "must be HH:mm")
            String timeOfDay,
            @Min(1) @Max(7) Integer dayOfWeek,
            @Min(1) @Max(28) Integer dayOfMonth
    ) {}

    /** {@code lastScheduledRunAt}: when the schedule last started a cycle. */
    public record ScheduleResponse(
            Long companyId,
            String companyName,
            String cadence,
            String timeOfDay,
            Integer dayOfWeek,
            Integer dayOfMonth,
            String timezone,
            Instant nextRunAt,
            Instant lastScheduledRunAt
    ) {}

    public record ExperimentDecision(@Size(max = 1000) String note) {}

    public record ExperimentRejection(@NotBlank @Size(max = 1000) String reason) {}
}
