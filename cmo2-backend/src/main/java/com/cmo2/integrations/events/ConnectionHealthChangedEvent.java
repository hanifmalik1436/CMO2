package com.cmo2.integrations.events;

/** A customer's platform connection went down ({@code healthy=false}) or recovered ({@code healthy=true}). */
public record ConnectionHealthChangedEvent(Long tenantId, Long companyId, String platformName,
                                           boolean healthy, String message) {}
