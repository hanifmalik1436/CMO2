package com.cmo2.assets.events;

/** The owner approved or rejected a draft. {@code approved} false means rejected. */
public record AssetReviewedEvent(Long tenantId, Long companyId, Long assetId, boolean approved, String note) {}
