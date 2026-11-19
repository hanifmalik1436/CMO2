package com.cmo2.docs.service;

import com.cmo2.customers.domain.CustomerProfile;
import com.cmo2.customers.repository.CustomerProfileRepository;
import com.cmo2.docs.client.DocClientException;
import com.cmo2.docs.client.SlackClient;
import com.cmo2.docs.domain.CompanyDocTargets;
import com.cmo2.docs.domain.DocProvider;
import com.cmo2.docs.domain.DocShareStatus;
import com.cmo2.docs.service.DocConnectionService.Resolved;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Records per-tool sharing outcomes for a company's workspace targets. For tools where automatic
 * sharing isn't available in the current integration model, it stores a deterministic warning.
 */
@Service
public class DocSharingService {

    private static final Pattern SIMPLE_EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final CustomerProfileRepository profiles;
    private final DocConnectionService connections;
    private final SlackClient slack;

    public DocSharingService(CustomerProfileRepository profiles, DocConnectionService connections, SlackClient slack) {
        this.profiles = profiles;
        this.connections = connections;
        this.slack = slack;
    }

    public void applyShareOutcomes(Long tenantId, Long companyId, String companyName, CompanyDocTargets targets) {
        String email = profiles.findByTenantIdAndCompanyId(tenantId, companyId).map(CustomerProfile::getEmail).map(this::clean).orElse(null);
        Instant at = Instant.now();
        if (email == null || !SIMPLE_EMAIL.matcher(email).matches()) {
            markAllWarnings(targets, at, "No valid customer owner email was found in this customer's profile.");
            return;
        }

        applyNotion(tenantId, targets, email, at);
        applyConfluence(tenantId, targets, email, at);
        applyLinear(tenantId, targets, email, at);
        applySlack(tenantId, companyName, targets, email, at);
    }

    private void applyNotion(Long tenantId, CompanyDocTargets targets, String email, Instant at) {
        if (targets.getNotionPageId() == null) {
            setNotion(targets, DocShareStatus.SKIPPED, "No Notion page exists for this company yet.", email, at);
            return;
        }
        if (connections.resolve(tenantId, DocProvider.NOTION).isEmpty()) {
            setNotion(targets, DocShareStatus.SKIPPED, "Notion is not connected for this tenant.", email, at);
            return;
        }
        setNotion(targets, DocShareStatus.WARNING,
                "Notion API sharing by email depends on workspace integration capabilities; share the page manually if needed.",
                email, at);
    }

    private void applyConfluence(Long tenantId, CompanyDocTargets targets, String email, Instant at) {
        if (targets.getConfluencePageId() == null) {
            setConfluence(targets, DocShareStatus.SKIPPED, "No Confluence page exists for this company yet.", email, at);
            return;
        }
        if (connections.resolve(tenantId, DocProvider.CONFLUENCE).isEmpty()) {
            setConfluence(targets, DocShareStatus.SKIPPED, "Confluence is not connected for this tenant.", email, at);
            return;
        }
        setConfluence(targets, DocShareStatus.WARNING,
                "Automatic Confluence sharing is not enabled in this build; grant page access to this email manually.",
                email, at);
    }

    private void applyLinear(Long tenantId, CompanyDocTargets targets, String email, Instant at) {
        if (targets.getLinearProjectId() == null) {
            setLinear(targets, DocShareStatus.SKIPPED, "No Linear project exists for this company yet.", email, at);
            return;
        }
        if (connections.resolve(tenantId, DocProvider.LINEAR).isEmpty()) {
            setLinear(targets, DocShareStatus.SKIPPED, "Linear is not connected for this tenant.", email, at);
            return;
        }
        setLinear(targets, DocShareStatus.WARNING,
                "Automatic Linear member invites are not enabled in this build; add this email to the project/team manually.",
                email, at);
    }

    private void applySlack(Long tenantId, String companyName, CompanyDocTargets targets, String email, Instant at) {
        Optional<Resolved> conn = connections.resolve(tenantId, DocProvider.SLACK);
        if (conn.isEmpty()) {
            setSlack(targets, DocShareStatus.SKIPPED, "Slack is not connected for this tenant.", email, at);
            return;
        }
        try {
            slack.post(conn.get().secret(), "*CMO² sharing notice* for " + companyName
                    + ": invite " + email + " to the relevant channel (webhook integrations cannot auto-invite by email).");
            setSlack(targets, DocShareStatus.WARNING,
                    "Slack webhook posted invite guidance. Webhooks cannot auto-invite by email; complete channel invite manually.",
                    email, at);
        } catch (DocClientException e) {
            setSlack(targets, DocShareStatus.FAILED, "Slack notice failed: " + clip(e.getMessage(), 1000), email, at);
        }
    }

    private void markAllWarnings(CompanyDocTargets targets, Instant at, String message) {
        setNotion(targets, DocShareStatus.WARNING, message, null, at);
        setConfluence(targets, DocShareStatus.WARNING, message, null, at);
        setLinear(targets, DocShareStatus.WARNING, message, null, at);
        setSlack(targets, DocShareStatus.WARNING, message, null, at);
    }

    private void setNotion(CompanyDocTargets t, String status, String msg, String email, Instant at) {
        t.setNotionShareStatus(status);
        t.setNotionShareMessage(clip(msg, 1000));
        t.setNotionSharedEmail(email);
        t.setNotionShareAttemptedAt(at);
    }

    private void setConfluence(CompanyDocTargets t, String status, String msg, String email, Instant at) {
        t.setConfluenceShareStatus(status);
        t.setConfluenceShareMessage(clip(msg, 1000));
        t.setConfluenceSharedEmail(email);
        t.setConfluenceShareAttemptedAt(at);
    }

    private void setLinear(CompanyDocTargets t, String status, String msg, String email, Instant at) {
        t.setLinearShareStatus(status);
        t.setLinearShareMessage(clip(msg, 1000));
        t.setLinearSharedEmail(email);
        t.setLinearShareAttemptedAt(at);
    }

    private void setSlack(CompanyDocTargets t, String status, String msg, String email, Instant at) {
        t.setSlackShareStatus(status);
        t.setSlackShareMessage(clip(msg, 1000));
        t.setSlackSharedEmail(email);
        t.setSlackShareAttemptedAt(at);
    }

    private String clean(String email) {
        return email == null ? null : email.trim().toLowerCase();
    }

    private static String clip(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}
