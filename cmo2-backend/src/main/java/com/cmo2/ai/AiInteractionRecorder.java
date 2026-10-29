package com.cmo2.ai;

import com.cmo2.ai.AiMessages.AiRequest;
import com.cmo2.ai.AiMessages.AiResult;
import com.cmo2.ai.domain.AiInteraction;
import com.cmo2.ai.repository.AiInteractionRepository;
import com.cmo2.shared.security.TenantContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Keeps a row per AI call in {@code ai_interactions}, and the per-customer monthly token total
 * that {@link AiService} checks. Rows are deleted after the retention period: prompts can carry
 * lead details, so they're kept only as long as cycle records need them (UK GDPR storage limitation).
 */
@Component
public class AiInteractionRecorder {

    private final AiInteractionRepository repository;
    private final long retentionDays;

    public AiInteractionRecorder(AiInteractionRepository repository,
                                 @Value("${cmo2.ai.interaction-retention-days:180}") long retentionDays) {
        this.repository = repository;
        this.retentionDays = retentionDays;
    }

    /** In its own transaction, so a call is on record even if the caller's work rolls back. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AiRequest request, AiResult result, AiCallScope.Scope scope) {
        AiInteraction row = new AiInteraction();
        row.setTenantId(TenantContext.requireTenantId());
        row.setCompanyId(scope.companyId());
        row.setIterationId(scope.iterationId());
        row.setStage(scope.stage());
        row.setKind(clip(request.kind() == null ? "UNKNOWN" : request.kind(), 64));
        row.setTier(request.tier().name());
        row.setModel(clip(result.model(), 80));
        row.setPromptText("SYSTEM:\n" + nullToEmpty(request.systemPrompt()) + "\n\nUSER:\n" + nullToEmpty(request.userPrompt()));
        row.setResponseText(result.text());
        row.setInputTokens(result.inputTokens());
        row.setOutputTokens(result.outputTokens());
        repository.save(row);
    }

    @Transactional(readOnly = true)
    public long companyTokensThisMonth(Long tenantId, Long companyId) {
        Instant monthStart = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        return repository.tokensSince(tenantId, companyId, monthStart);
    }

    @Scheduled(cron = "${cmo2.ai.interaction-retention-cron:0 30 3 * * *}")
    @Transactional
    public void purgeExpired() {
        if (retentionDays > 0) {
            repository.deleteOlderThan(Instant.now().minus(Duration.ofDays(retentionDays)));
        }
    }

    private static String clip(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
