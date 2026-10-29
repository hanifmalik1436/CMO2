package com.cmo2.reports.domain;

import com.cmo2.shared.tenancy.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * A generated report (PDF) stored for the Reports module. {@code companyId} is null for a
 * portfolio-wide report. The rendered PDF bytes are kept base64-encoded in
 * {@code content_base64} so the document survives without a separate blob store.
 */
@Entity
@Table(name = "reports")
@Getter
@Setter
public class Report extends BaseTenantEntity {

    @Column(name = "company_id")
    private Long companyId; // null = portfolio report

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ReportType type = ReportType.ON_DEMAND;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(length = 32)
    private String period;

    @Column(nullable = false, length = 8)
    private String format = "PDF";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ReportStatus status = ReportStatus.GENERATED;

    @Lob
    @Column(columnDefinition = "LONGTEXT", name = "content_base64")
    private String contentBase64;
}
