package com.cmo2.docs.service;

import com.cmo2.integrations.events.AgencyConnectionFailedEvent;
import com.cmo2.integrations.events.ConnectionHealthChangedEvent;
import com.cmo2.integrations.events.CustomerEnabledEvent;
import com.cmo2.shared.notify.TeamNotifier;
import com.cmo2.tenancy.repository.CompanyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Reacts to integration events after they commit: sets up a newly enabled customer's workspace
 * pages, and tells the team's Slack when a customer's GHL connection goes down or recovers.
 * Failures here are logged, never thrown back into the action that raised the event.
 */
@Component
public class DocsEventListener {

    private static final Logger log = LoggerFactory.getLogger(DocsEventListener.class);

    private final DocTargetService targets;
    private final TeamNotifier team;
    private final CompanyRepository companies;

    public DocsEventListener(DocTargetService targets, TeamNotifier team, CompanyRepository companies) {
        this.targets = targets;
        this.team = team;
        this.companies = companies;
    }

    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onCustomerEnabled(CustomerEnabledEvent e) {
        try {
            targets.provision(e.tenantId(), e.companyId(), e.companyName());
        } catch (Exception ex) {
            log.warn("Workspace setup for new customer {} failed: {}", e.companyId(), ex.getMessage());
        }
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void onConnectionHealthChanged(ConnectionHealthChangedEvent e) {
        String name = companies.findById(e.companyId())
                .filter(c -> e.tenantId().equals(c.getTenantId()))
                .map(c -> c.getName())
                .orElse("Customer #" + e.companyId());
        String text = e.healthy()
                ? ":large_green_circle: *" + name + "* · " + e.platformName() + " connection is working again."
                : ":red_circle: *" + name + "* · " + e.platformName() + " connection is down"
                        + (e.message() == null ? "." : ": " + e.message());
        team.notify(e.tenantId(), text);
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void onAgencyConnectionFailed(AgencyConnectionFailedEvent e) {
        team.notify(e.tenantId(), ":red_circle: Your GoHighLevel agency connection stopped working. "
                + "Reconnect it in CMO² → Customers. (" + e.message() + ")");
    }
}
