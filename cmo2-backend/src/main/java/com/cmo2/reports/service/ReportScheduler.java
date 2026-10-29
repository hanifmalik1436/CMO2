package com.cmo2.reports.service;

import com.cmo2.reports.domain.ReportType;
import com.cmo2.shared.security.TenantContext;
import com.cmo2.tenancy.domain.Company;
import com.cmo2.tenancy.repository.CompanyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Generates a weekly portfolio PDF for every tenant automatically — the second half of the
 * Phase 6 exit criterion ("weekly report PDF generates and lands in the Reports module").
 *
 * <p>The sweep runs with NO tenant context, so {@code findAll} returns companies across all
 * tenants (the filter is disabled when unbound). It derives the distinct tenant set, then
 * binds each tenant before generating that tenant's report.
 */
@Component
public class ReportScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReportScheduler.class);

    private final CompanyRepository companies;
    private final ReportService reportService;

    public ReportScheduler(CompanyRepository companies, ReportService reportService) {
        this.companies = companies;
        this.reportService = reportService;
    }

    @Scheduled(
            fixedDelayString = "${cmo2.reports.weekly-interval-ms:604800000}",
            initialDelayString = "${cmo2.reports.weekly-initial-delay-ms:3600000}")
    public void generateWeeklyReports() {
        List<Company> all = companies.findAll(); // unbound context → all tenants
        Set<Long> tenantIds = new LinkedHashSet<>();
        for (Company c : all) {
            tenantIds.add(c.getTenantId());
        }
        if (tenantIds.isEmpty()) {
            return;
        }
        log.info("Generating weekly portfolio reports for {} tenant(s)", tenantIds.size());
        for (Long tenantId : tenantIds) {
            TenantContext.set(tenantId, null);
            try {
                reportService.generatePortfolioInternal(ReportType.WEEKLY);
            } catch (Exception e) {
                log.warn("Weekly report generation failed for tenant {}: {}", tenantId, e.getMessage());
            } finally {
                TenantContext.clear();
            }
        }
    }
}
