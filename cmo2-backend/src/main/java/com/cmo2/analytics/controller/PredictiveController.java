package com.cmo2.analytics.controller;

import com.cmo2.analytics.service.ForecastService;
import com.cmo2.analytics.service.ForecastService.Forecast;
import com.cmo2.analytics.service.WhatIfService;
import com.cmo2.analytics.service.WhatIfService.Scenario;
import com.cmo2.analytics.service.WhatIfService.WhatIfResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Advanced analytics (Phase 8): KPI forecasting and What-If scenario simulation.
 */
@RestController
@RequestMapping("/api")
@Tag(name = "Predictive Analytics")
public class PredictiveController {

    private final ForecastService forecastService;
    private final WhatIfService whatIfService;

    public PredictiveController(ForecastService forecastService, WhatIfService whatIfService) {
        this.forecastService = forecastService;
        this.whatIfService = whatIfService;
    }

    @GetMapping("/companies/{companyId}/analytics/forecast")
    @Operation(summary = "Forecast a KPI forward (OLS trend) with projection, trend and confidence")
    public Forecast forecast(@PathVariable Long companyId,
                             @RequestParam String metric,
                             @RequestParam(defaultValue = "6") int horizon) {
        return forecastService.forecast(companyId, metric, horizon);
    }

    @PostMapping("/companies/{companyId}/analytics/what-if")
    @Operation(summary = "Simulate how spend / CPL / conversion changes ripple through the funnel")
    public WhatIfResult whatIf(@PathVariable Long companyId, @RequestBody Scenario scenario) {
        return whatIfService.simulate(companyId, scenario);
    }
}
