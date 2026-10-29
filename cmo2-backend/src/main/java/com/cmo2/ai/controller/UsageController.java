package com.cmo2.ai.controller;

import com.cmo2.ai.CostGuard;
import com.cmo2.ai.TokenAccountant;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Surfaces the per-tenant AI token meter (NON-NEGOTIABLE RULE #8 — usage visible in the UI).
 */
@RestController
@RequestMapping("/api/usage")
@Tag(name = "AI usage")
public class UsageController {

    private final TokenAccountant tokenAccountant;
    private final CostGuard costGuard;

    public UsageController(TokenAccountant tokenAccountant, CostGuard costGuard) {
        this.tokenAccountant = tokenAccountant;
        this.costGuard = costGuard;
    }

    public record UsageResponse(String period, long tokensUsed, long tokenCeiling) {}

    @GetMapping("/current")
    @Operation(summary = "Current billing period's AI token usage vs the plan ceiling")
    public UsageResponse current() {
        return new UsageResponse(
                TokenAccountant.currentPeriod(),
                tokenAccountant.currentPeriodTokens(),
                costGuard.ceilingForCurrentTenant());
    }
}
