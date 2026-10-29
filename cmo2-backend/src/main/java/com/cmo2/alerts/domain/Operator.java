package com.cmo2.alerts.domain;

/** Threshold comparison for an alert rule. */
public enum Operator {
    GT,   // value > threshold
    LT,   // value < threshold
    GTE,  // value >= threshold
    LTE;  // value <= threshold

    public boolean test(double value, double threshold) {
        return switch (this) {
            case GT -> value > threshold;
            case LT -> value < threshold;
            case GTE -> value >= threshold;
            case LTE -> value <= threshold;
        };
    }
}
