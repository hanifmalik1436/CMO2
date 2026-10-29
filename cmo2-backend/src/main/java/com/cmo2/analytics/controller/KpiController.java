package com.cmo2.analytics.controller;

import com.cmo2.analytics.service.KpiQueryService;
import com.cmo2.analytics.service.KpiQueryService.LatestKpi;
import com.cmo2.analytics.service.KpiTrendService;
import com.cmo2.analytics.service.KpiTrendService.DailyValue;
import com.cmo2.analytics.service.KpiTrendService.Trend;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
@Tag(name = "Analytics / KPIs")
public class KpiController {

    private final KpiQueryService kpiQueryService;
    private final KpiTrendService kpiTrendService;

    public KpiController(KpiQueryService kpiQueryService, KpiTrendService kpiTrendService) {
        this.kpiQueryService = kpiQueryService;
        this.kpiTrendService = kpiTrendService;
    }

    @GetMapping("/companies/{companyId}/kpis/latest")
    @Operation(summary = "Latest value per KPI metric for a company (drives the dashboard)")
    public List<LatestKpi> latest(@PathVariable Long companyId) {
        return kpiQueryService.latestByCompany(companyId);
    }

    @GetMapping("/companies/{companyId}/kpis/daily")
    @Operation(summary = "Each metric per day in the customer's timezone (latest reading of the day)")
    public List<DailyValue> daily(@PathVariable Long companyId, @RequestParam(defaultValue = "14") int days) {
        return kpiTrendService.daily(companyId, days);
    }

    @GetMapping("/companies/{companyId}/kpis/week")
    @Operation(summary = "Last 7 days against the 7 before (daily figures summed, levels latest)")
    public List<Trend> week(@PathVariable Long companyId) {
        return kpiTrendService.week(companyId);
    }
}
