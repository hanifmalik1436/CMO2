package com.cmo2.analytics.service;

import com.cmo2.analytics.domain.KpiDailyRollup;
import com.cmo2.analytics.repository.KpiDailyRollupRepository;
import com.cmo2.integrations.core.Metrics;
import com.cmo2.integrations.core.Timezones;
import com.cmo2.shared.exceptions.ApiException;
import com.cmo2.shared.security.TenantContext;
import com.cmo2.shared.tenancy.TenantGuard;
import com.cmo2.tenancy.domain.Company;
import com.cmo2.tenancy.repository.CompanyRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Day-by-day figures for a customer, and this week compared with the last, computed in Java from
 * {@code kpi_daily_rollups}. AI calls only ever see the compact result, never raw snapshots.
 */
@Service
public class KpiTrendService {

    static final int WINDOW_DAYS = 7;
    private static final int MAX_DAYS = 90;

    private final KpiDailyRollupRepository rollups;
    private final CompanyRepository companies;
    private final Clock clock;

    @Autowired
    public KpiTrendService(KpiDailyRollupRepository rollups, CompanyRepository companies) {
        this(rollups, companies, Clock.systemUTC());
    }

    KpiTrendService(KpiDailyRollupRepository rollups, CompanyRepository companies, Clock clock) {
        this.rollups = rollups;
        this.companies = companies;
        this.clock = clock;
    }

    /** One metric on one day; several platforms' readings for the same day are added together. */
    public record DailyValue(LocalDate date, String metric, double value) {}

    /**
     * A metric over the last 7 days (today so far included) against the 7 before. For daily
     * figures the values are sums; for levels they are the latest reading in each window.
     * {@code previous} is null when there is no reading from that far back.
     */
    public record Trend(String metric, boolean daily, double current, Double previous) {}

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','STRATEGIST','ANALYST','VIEWER')")
    public List<DailyValue> daily(Long companyId, int days) {
        Company company = TenantGuard.owned(companies.findById(companyId))
                .orElseThrow(() -> ApiException.notFound("Company not found"));
        int span = Math.min(Math.max(days, 1), MAX_DAYS);
        Map<LocalDate, Map<String, Double>> byDay = byDay(company.getTenantId(), company, span);
        List<DailyValue> out = new ArrayList<>();
        byDay.forEach((date, metrics) -> metrics.forEach((m, v) -> out.add(new DailyValue(date, m, round(v)))));
        return out;
    }

    /** The same comparison the AI is given, for the owner's customer screen. */
    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','STRATEGIST','ANALYST','VIEWER')")
    public List<Trend> week(Long companyId) {
        Company company = TenantGuard.owned(companies.findById(companyId))
                .orElseThrow(() -> ApiException.notFound("Company not found"));
        return weekOverWeek(company.getTenantId(), companyId);
    }

    /** Internal: no principal on loop threads, so the caller names the tenant. */
    @Transactional(readOnly = true)
    public List<Trend> weekOverWeek(Long tenantId, Long companyId) {
        Company company = companies.findById(companyId)
                .filter(c -> tenantId.equals(c.getTenantId()))
                .orElse(null);
        if (company == null) {
            return List.of();
        }
        LocalDate today = today(company);
        LocalDate currentFrom = today.minusDays(WINDOW_DAYS - 1L);
        Map<LocalDate, Map<String, Double>> byDay = byDay(tenantId, company, WINDOW_DAYS * 2);

        Map<String, Trend> trends = new LinkedHashMap<>();
        for (String metric : order(byDay)) {
            boolean daily = Metrics.DAILY.contains(metric);
            Double current = null;
            Double previous = null;
            for (Map.Entry<LocalDate, Map<String, Double>> day : byDay.entrySet()) {
                Double v = day.getValue().get(metric);
                if (v == null) continue;
                boolean inCurrent = !day.getKey().isBefore(currentFrom);
                if (daily) {
                    if (inCurrent) current = (current == null ? 0 : current) + v;
                    else previous = (previous == null ? 0 : previous) + v;
                } else if (inCurrent) {
                    current = v; // days are ascending, so the last one wins
                } else {
                    previous = v;
                }
            }
            if (current != null) {
                trends.put(metric, new Trend(metric, daily, round(current), previous == null ? null : round(previous)));
            }
        }
        return new ArrayList<>(trends.values());
    }

    /** Rollup rows read so far for a customer over the last {@code days} days. */
    @Transactional(readOnly = true)
    public int dataPoints(Long tenantId, Long companyId, int days) {
        Company company = companies.findById(companyId).filter(c -> tenantId.equals(c.getTenantId())).orElse(null);
        if (company == null) return 0;
        return rollups.findByTenantIdAndCompanyIdAndMetricDateGreaterThanEqualOrderByMetricDateAsc(
                tenantId, companyId, today(company).minusDays(days - 1L)).size();
    }

    private Map<LocalDate, Map<String, Double>> byDay(Long tenantId, Company company, int days) {
        LocalDate from = today(company).minusDays(days - 1L);
        Map<LocalDate, Map<String, Double>> byDay = new TreeMap<>();
        for (KpiDailyRollup r : rollups.findByTenantIdAndCompanyIdAndMetricDateGreaterThanEqualOrderByMetricDateAsc(
                tenantId, company.getId(), from)) {
            byDay.computeIfAbsent(r.getMetricDate(), d -> new LinkedHashMap<>())
                    .merge(r.getMetric(), r.getValue(), Double::sum);
        }
        return byDay;
    }

    /** Known metrics first, in their canonical order, then anything else alphabetically. */
    private static List<String> order(Map<LocalDate, Map<String, Double>> byDay) {
        List<String> seen = new ArrayList<>();
        byDay.values().forEach(m -> m.keySet().forEach(k -> { if (!seen.contains(k)) seen.add(k); }));
        List<String> out = new ArrayList<>();
        for (String m : Metrics.ORDER) if (seen.remove(m)) out.add(m);
        seen.sort(String::compareTo);
        out.addAll(seen);
        return out;
    }

    private LocalDate today(Company company) {
        return LocalDate.now(clock.withZone(Timezones.firstValid(company.getTimezone())));
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
