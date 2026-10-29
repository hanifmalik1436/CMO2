package com.cmo2.assets.events;

import com.cmo2.shared.notify.TeamNotifier;
import com.cmo2.tenancy.repository.CompanyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Reacts to new drafts without the generator knowing. Today: tell the team a draft is waiting
 * for approval. Drafts are never published from here; publishing always needs a person to approve.
 */
@Component
public class AssetEventListener {

    private static final Logger log = LoggerFactory.getLogger(AssetEventListener.class);

    private final TeamNotifier team;
    private final CompanyRepository companies;

    public AssetEventListener(TeamNotifier team, CompanyRepository companies) {
        this.team = team;
        this.companies = companies;
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void onAssetGenerated(AssetGeneratedEvent event) {
        try {
            String company = companies.findById(event.companyId())
                    .filter(c -> event.tenantId() != null && event.tenantId().equals(c.getTenantId()))
                    .map(c -> c.getName()).orElse(null);
            if (company == null) return;
            team.notify(event.tenantId(), ":memo: New draft for " + company + " (" + label(event.type())
                    + ", English and Spanish) is waiting for your approval in CMO².");
        } catch (RuntimeException e) {
            log.warn("Could not announce draft {}: {}", event.assetId(), e.getMessage());
        }
    }

    private static String label(String type) {
        String s = type.replace('_', ' ').toLowerCase();
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
