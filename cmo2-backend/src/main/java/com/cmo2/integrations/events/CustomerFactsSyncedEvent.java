package com.cmo2.integrations.events;

import com.cmo2.integrations.core.SyncModels.CustomerFacts;

/** A sync read the customer's own business details from their platform account. */
public record CustomerFactsSyncedEvent(Long tenantId, Long companyId, CustomerFacts facts) {}
