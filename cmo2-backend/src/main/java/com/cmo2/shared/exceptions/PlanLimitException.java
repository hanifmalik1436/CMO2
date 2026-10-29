package com.cmo2.shared.exceptions;

/**
 * Thrown when a tenant action would exceed its plan's quota (companies, connections, seats,
 * AI tokens). Rendered as HTTP 402 with a structured {@code details} payload so the UI can
 * show a friendly, specific upgrade prompt instead of a generic error.
 */
public class PlanLimitException extends RuntimeException {

    private final String limitType;     // companies | connections | seats | ai_tokens
    private final long current;
    private final long max;
    private final String plan;          // current plan name
    private final String suggestedPlan; // plan to upgrade to (may be null at top tier)

    public PlanLimitException(String message, String limitType, long current, long max,
                              String plan, String suggestedPlan) {
        super(message);
        this.limitType = limitType;
        this.current = current;
        this.max = max;
        this.plan = plan;
        this.suggestedPlan = suggestedPlan;
    }

    public String getLimitType() { return limitType; }
    public long getCurrent() { return current; }
    public long getMax() { return max; }
    public String getPlan() { return plan; }
    public String getSuggestedPlan() { return suggestedPlan; }
}
