package com.cmo2.customers.domain;

/** Where a canon version stands: the owner's working copy, the one the AI gets, or an older one. */
public enum CanonStatus {
    DRAFT,
    APPROVED,
    RETIRED
}
