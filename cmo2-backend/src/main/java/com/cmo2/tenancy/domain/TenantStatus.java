package com.cmo2.tenancy.domain;

/** Subscription/lifecycle status, driven later by Stripe webhooks (Phase 7). */
public enum TenantStatus {
    TRIAL,
    ACTIVE,
    PAST_DUE,
    SUSPENDED,
    CANCELED
}
