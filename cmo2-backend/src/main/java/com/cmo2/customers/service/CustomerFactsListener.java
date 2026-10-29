package com.cmo2.customers.service;

import com.cmo2.integrations.events.CustomerFactsSyncedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Copies what a sync learned about the business into its profile, after the sync has committed. */
@Component
public class CustomerFactsListener {

    private static final Logger log = LoggerFactory.getLogger(CustomerFactsListener.class);

    private final CustomerProfileService profiles;

    public CustomerFactsListener(CustomerProfileService profiles) {
        this.profiles = profiles;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onFactsSynced(CustomerFactsSyncedEvent event) {
        try {
            profiles.applyFacts(event.tenantId(), event.companyId(), event.facts());
        } catch (RuntimeException e) {
            log.warn("Could not store GHL details for company {}: {}", event.companyId(), e.getMessage());
        }
    }
}
