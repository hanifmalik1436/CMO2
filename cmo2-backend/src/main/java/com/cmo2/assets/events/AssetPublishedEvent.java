package com.cmo2.assets.events;

import java.time.Instant;

/** An approved asset was accepted by the platform (for social posts: scheduled for {@code goesOutAt}). */
public record AssetPublishedEvent(Long tenantId, Long companyId, Long assetId, Instant goesOutAt) {}
