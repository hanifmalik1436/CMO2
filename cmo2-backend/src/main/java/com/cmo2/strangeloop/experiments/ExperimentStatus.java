package com.cmo2.strangeloop.experiments;

/**
 * PROPOSED → APPLIED (the owner approved it, or approved and published its draft) → MEASURED in a
 * later cycle. REJECTED when the owner turns it down; FAILED when applying it didn't work.
 */
public enum ExperimentStatus {
    PROPOSED,
    APPLIED,
    MEASURED,
    REJECTED,
    FAILED
}
