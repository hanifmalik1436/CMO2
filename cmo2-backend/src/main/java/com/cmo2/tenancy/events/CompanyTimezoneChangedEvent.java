package com.cmo2.tenancy.events;

/** A customer's timezone changed, so anything scheduled in their local time must move with it. */
public record CompanyTimezoneChangedEvent(Long tenantId, Long companyId, String timezone) {}
