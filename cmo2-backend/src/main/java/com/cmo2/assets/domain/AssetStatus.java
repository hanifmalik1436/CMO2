package com.cmo2.assets.domain;

/**
 * A draft is PENDING_APPROVAL until the owner or an admin approves or rejects it; only an
 * APPROVED asset can be published (then DEPLOYED). GENERATED and TRANSLATED are kept for rows
 * written before approvals existed.
 */
public enum AssetStatus {
    PENDING_APPROVAL,
    APPROVED,
    REJECTED,
    DEPLOYED,
    ARCHIVED,
    GENERATED,
    TRANSLATED
}
