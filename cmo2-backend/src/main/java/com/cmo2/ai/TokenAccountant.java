package com.cmo2.ai;

import com.cmo2.ai.domain.UsageRecord;
import com.cmo2.ai.repository.UsageRecordRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.YearMonth;

/**
 * Records and reports per-tenant AI token usage by billing period. Backs both
 * {@link CostGuard} (enforcement) and the usage dashboard (visibility).
 */
@Service
public class TokenAccountant {

    private final UsageRecordRepository repository;

    public TokenAccountant(UsageRecordRepository repository) {
        this.repository = repository;
    }

    public static String currentPeriod() {
        return YearMonth.now().toString(); // e.g. "2026-06"
    }

    @Transactional
    public void record(long tokens) {
        if (tokens <= 0) return;
        String period = currentPeriod();
        UsageRecord record = repository
                .findFirstByMetricAndPeriod(UsageRecord.METRIC_AI_TOKENS, period)
                .orElseGet(() -> {
                    UsageRecord r = new UsageRecord();
                    r.setMetric(UsageRecord.METRIC_AI_TOKENS);
                    r.setPeriod(period);
                    r.setQuantity(0);
                    return r;
                });
        record.setQuantity(record.getQuantity() + tokens);
        repository.save(record);
    }

    @Transactional(readOnly = true)
    public long currentPeriodTokens() {
        return repository.sumQuantity(UsageRecord.METRIC_AI_TOKENS, currentPeriod());
    }
}
