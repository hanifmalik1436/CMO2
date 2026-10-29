package com.cmo2.reports.service;

import com.cmo2.analytics.domain.KpiSnapshot;
import com.cmo2.analytics.repository.KpiSnapshotRepository;
import com.cmo2.integrations.core.Metrics;
import com.cmo2.reports.domain.Report;
import com.cmo2.reports.domain.ReportStatus;
import com.cmo2.reports.domain.ReportType;
import com.cmo2.reports.dto.ReportDtos.ReportSummary;
import com.cmo2.reports.repository.ReportRepository;
import com.cmo2.reports.service.ReportGenerator.KpiRow;
import com.cmo2.shared.audit.AuditService;
import com.cmo2.shared.exceptions.ApiException;
import com.cmo2.shared.tenancy.TenantGuard;
import com.cmo2.tenancy.domain.Company;
import com.cmo2.tenancy.repository.CompanyRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Generates, stores, lists and serves leadership PDF reports. Two flavours:
 * a single-company report and a portfolio roll-up. The actual PDF rendering lives in
 * {@link ReportGenerator}; this service owns persistence, tenancy and authorization.
 *
 * <p>The weekly scheduler ({@link ReportScheduler}) calls {@link #generatePortfolioInternal}
 * directly with a tenant already bound — that path is intentionally NOT method-secured
 * because there is no authenticated principal on a background thread.
 */
@Service
public class ReportService {

    private static final Set<String> SUMMED = Set.of(Metrics.LEADS, Metrics.APPOINTMENTS, Metrics.WON,
            Metrics.REVENUE, Metrics.PIPELINE_VALUE);

    private static final Map<String, String> LABELS = Map.of(
            Metrics.LEADS, "Leads",
            Metrics.CPL, "Cost per Lead",
            Metrics.ROAS, "Return on Ad Spend",
            Metrics.CTR, "Click-Through Rate",
            Metrics.CONVERSION_RATE, "Conversion Rate",
            Metrics.APPOINTMENTS, "Appointments",
            Metrics.WON, "Deals Won",
            Metrics.REVENUE, "Revenue",
            Metrics.PIPELINE_VALUE, "Open Pipeline Value");

    private final KpiSnapshotRepository snapshots;
    private final CompanyRepository companies;
    private final ReportRepository reports;
    private final ReportGenerator generator;
    private final AuditService audit;

    public ReportService(KpiSnapshotRepository snapshots, CompanyRepository companies,
                         ReportRepository reports, ReportGenerator generator, AuditService audit) {
        this.snapshots = snapshots;
        this.companies = companies;
        this.reports = reports;
        this.generator = generator;
        this.audit = audit;
    }

    /** Bytes + filename for an HTTP download. */
    public record Download(String filename, byte[] content) {}

    // ---- On-demand (user-triggered, secured) -------------------------------

    @Transactional
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','STRATEGIST','ANALYST')")
    public ReportSummary generateForCompany(Long companyId) {
        Company company = TenantGuard.owned(companies.findById(companyId))
                .orElseThrow(() -> ApiException.notFound("Company not found"));
        Report report = buildCompanyReport(company, ReportType.ON_DEMAND);
        return ReportSummary.of(report);
    }

    @Transactional
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','STRATEGIST','ANALYST')")
    public ReportSummary generatePortfolio() {
        Report report = generatePortfolioInternal(ReportType.ON_DEMAND);
        return ReportSummary.of(report);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','STRATEGIST','ANALYST','VIEWER')")
    public List<ReportSummary> list(int limit) {
        return reports.findByOrderByCreatedAtDesc(PageRequest.of(0, Math.min(Math.max(limit, 1), 200)))
                .stream().map(ReportSummary::of).toList();
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','STRATEGIST','ANALYST','VIEWER')")
    public Download download(Long reportId) {
        Report report = TenantGuard.owned(reports.findById(reportId))
                .orElseThrow(() -> ApiException.notFound("Report not found"));
        if (report.getContentBase64() == null) {
            throw ApiException.notFound("Report has no content");
        }
        byte[] bytes = Base64.getDecoder().decode(report.getContentBase64());
        return new Download("report-" + report.getId() + ".pdf", bytes);
    }

    // ---- Internal builders (no @PreAuthorize — also used by the scheduler) ---

    /** Builds + persists a portfolio report for the currently-bound tenant. */
    @Transactional
    public Report generatePortfolioInternal(ReportType type) {
        List<Company> all = companies.findAllByOrderByCreatedAtDesc();
        List<KpiRow> rows = new ArrayList<>();
        for (String metric : Metrics.ALL) {
            double total = 0;
            int n = 0;
            for (Company c : all) {
                var latest = snapshots.findFirstByCompanyIdAndMetricOrderByCapturedAtDesc(c.getId(), metric);
                if (latest.isPresent()) {
                    total += latest.get().getValue();
                    n++;
                }
            }
            double value = n == 0 ? 0 : (SUMMED.contains(metric) ? total : total / n);
            rows.add(new KpiRow(LABELS.getOrDefault(metric, metric), format(metric, value)));
        }
        String subtitle = "Portfolio roll-up across " + all.size()
                + (all.size() == 1 ? " company" : " companies");
        byte[] pdf = generator.render("Portfolio Marketing Report", subtitle, periodFor(type), rows);
        return save(null, type, "Portfolio Marketing Report", periodFor(type), pdf);
    }

    private Report buildCompanyReport(Company company, ReportType type) {
        List<KpiRow> rows = new ArrayList<>();
        for (String metric : Metrics.ALL) {
            var latest = snapshots.findFirstByCompanyIdAndMetricOrderByCapturedAtDesc(company.getId(), metric);
            double value = latest.map(KpiSnapshot::getValue).orElse(0.0);
            rows.add(new KpiRow(LABELS.getOrDefault(metric, metric), format(metric, value)));
        }
        String title = "Marketing Report — " + company.getName();
        byte[] pdf = generator.render(title, company.getIndustry(), periodFor(type), rows);
        return save(company.getId(), type, title, periodFor(type), pdf);
    }

    private Report save(Long companyId, ReportType type, String title, String period, byte[] pdf) {
        Report report = new Report();
        report.setCompanyId(companyId);
        report.setType(type);
        report.setTitle(title);
        report.setPeriod(period);
        report.setFormat("PDF");
        report.setStatus(ReportStatus.GENERATED);
        report.setContentBase64(Base64.getEncoder().encodeToString(pdf));
        report = reports.save(report);
        audit.record("REPORT_GENERATED", "Report", String.valueOf(report.getId()), title);
        return report;
    }

    private String periodFor(ReportType type) {
        return type == ReportType.WEEKLY ? "Last 7 days" : "Current snapshot";
    }

    private String format(String metric, double value) {
        return switch (metric) {
            case Metrics.LEADS, Metrics.APPOINTMENTS, Metrics.WON -> String.valueOf(Math.round(value));
            case Metrics.CPL -> "$" + round2(value);
            case Metrics.ROAS -> round2(value) + "x";
            case Metrics.CTR, Metrics.CONVERSION_RATE -> round2(value) + "%";
            default -> round2(value);
        };
    }

    private String round2(double v) {
        return String.valueOf(Math.round(v * 100.0) / 100.0);
    }
}
