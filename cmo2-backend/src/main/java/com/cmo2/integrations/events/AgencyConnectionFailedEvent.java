package com.cmo2.integrations.events;

/** The tenant's GoHighLevel agency connection stopped working and needs reconnecting. */
public record AgencyConnectionFailedEvent(Long tenantId, String message) {}
