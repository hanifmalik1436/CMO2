package com.cmo2.assets.events;

/**
 * Published when an asset (and its translation) is generated. Decouples the generator
 * from downstream listeners — translation, auto-documentation, deployment, and
 * Strange Loop Stage-2 learning attach here without the generator knowing they exist
 * (the eventing pattern from the architecture doc).
 */
public record AssetGeneratedEvent(Long tenantId, Long companyId, Long assetId, String type) {
}
