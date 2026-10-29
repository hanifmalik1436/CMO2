package com.cmo2.docs.service;

import com.cmo2.docs.client.DocClientException;
import com.cmo2.docs.client.SlackClient;
import com.cmo2.docs.domain.DocProvider;
import com.cmo2.shared.notify.TeamNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Delivers team notifications to the agency's connected Slack webhook. */
@Component
public class SlackTeamNotifier implements TeamNotifier {

    private static final Logger log = LoggerFactory.getLogger(SlackTeamNotifier.class);

    private final DocConnectionService connections;
    private final SlackClient slack;

    public SlackTeamNotifier(DocConnectionService connections, SlackClient slack) {
        this.connections = connections;
        this.slack = slack;
    }

    @Override
    public boolean notify(Long tenantId, String text) {
        if (tenantId == null) {
            return false;
        }
        return connections.resolve(tenantId, DocProvider.SLACK).map(slackConn -> {
            try {
                slack.post(slackConn.secret(), text);
                return true;
            } catch (DocClientException e) {
                log.warn("Team Slack notification failed for tenant {}: {}", tenantId, e.getMessage());
                return false;
            }
        }).orElse(false);
    }
}
