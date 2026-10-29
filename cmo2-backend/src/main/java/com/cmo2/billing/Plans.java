package com.cmo2.billing;

import com.cmo2.tenancy.domain.PlanCode;

/**
 * Plan presentation metadata (display name + monthly price). The enforced limits live on
 * {@link PlanCode}; this adds the commercial face of each plan for the billing UI.
 */
public final class Plans {

    private Plans() {}

    public static long monthlyPriceCents(PlanCode plan) {
        return switch (plan) {
            case STARTER -> 9_700L;     // $97/mo
            case PRO -> 29_700L;        // $297/mo
            case ENTERPRISE -> 99_700L; // $997/mo
        };
    }

    public static String displayName(PlanCode plan) {
        return switch (plan) {
            case STARTER -> "Starter";
            case PRO -> "Pro";
            case ENTERPRISE -> "Enterprise";
        };
    }

    /** The plan a customer is nudged toward when they hit a limit (null at the top tier). */
    public static PlanCode nextTier(PlanCode plan) {
        return switch (plan) {
            case STARTER -> PlanCode.PRO;
            case PRO -> PlanCode.ENTERPRISE;
            case ENTERPRISE -> null;
        };
    }
}
