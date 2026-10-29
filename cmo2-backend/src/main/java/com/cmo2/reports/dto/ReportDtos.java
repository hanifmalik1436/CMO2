package com.cmo2.reports.dto;

import com.cmo2.reports.domain.Report;

import java.time.Instant;

/** Transport types for the Reports module. */
public final class ReportDtos {

    private ReportDtos() {}

    /** Metadata only — never carries the (large) base64 PDF payload. */
    public record ReportSummary(
            Long id,
            Long companyId,
            String type,
            String title,
            String period,
            String format,
            String status,
            Instant createdAt) {

        public static ReportSummary of(Report r) {
            return new ReportSummary(
                    r.getId(),
                    r.getCompanyId(),
                    r.getType().name(),
                    r.getTitle(),
                    r.getPeriod(),
                    r.getFormat(),
                    r.getStatus().name(),
                    r.getCreatedAt());
        }
    }
}
