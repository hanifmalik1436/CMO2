package com.cmo2.analytics.controller;

import com.cmo2.analytics.service.AggregationService;
import com.cmo2.analytics.service.AggregationService.MetricAggregate;
import com.cmo2.analytics.service.AggregationService.Point;
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
@Tag(name = "Analytics")
public class AnalyticsController {

    private final AggregationService aggregation;

    public AnalyticsController(AggregationService aggregation) {
        this.aggregation = aggregation;
    }

    @GetMapping("/companies/{companyId}/analytics/timeseries")
    @Operation(summary = "Time series of one KPI metric for a company (oldest→newest)")
    public List<Point> timeseries(@PathVariable Long companyId,
                                  @RequestParam String metric,
                                  @RequestParam(defaultValue = "30") int limit) {
        return aggregation.timeseries(companyId, metric, limit);
    }

    @GetMapping("/analytics/portfolio")
    @Operation(summary = "Portfolio KPI roll-up across the tenant's companies")
    public List<MetricAggregate> portfolio() {
        return aggregation.portfolio();
    }
}
