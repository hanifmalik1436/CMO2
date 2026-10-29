package com.cmo2.strangeloop.experiments;

import com.cmo2.integrations.gohighlevel.capability.GhlCapability;
import com.cmo2.integrations.gohighlevel.capability.RiskTier;

/**
 * What Stage 5 may propose. Each maps to at most one entry of the GHL capability registry, and the
 * AI only ever picks one of these and fills in its typed fields; it never builds a GHL call itself.
 */
public enum ExperimentAction {
    /** A social post draft; it goes through Approvals and only reaches GHL when the owner publishes it. */
    DRAFT_SOCIAL_POST(GhlCapability.CREATE_SOCIAL_POST),
    /** New text for one of the sub-account's existing custom values (which its workflows and emails may use). */
    UPDATE_CUSTOM_VALUE(GhlCapability.UPDATE_CUSTOM_VALUE),
    /** Something for the owner to do; CMO² calls nothing. */
    SUGGESTION(null);

    public final GhlCapability capability;

    ExperimentAction(GhlCapability capability) {
        this.capability = capability;
    }

    public RiskTier tier() {
        return capability == null ? RiskTier.READ : capability.tier;
    }
}
