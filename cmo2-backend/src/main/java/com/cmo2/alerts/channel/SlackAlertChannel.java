package com.cmo2.alerts.channel;

import com.cmo2.alerts.domain.Alert;
import com.cmo2.alerts.domain.ChannelType;
import com.cmo2.shared.notify.TeamNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.Map;

/**
 * Slack delivery. Posts to the rule's own webhook if it has one; otherwise to the agency's
 * connected Slack (Settings → Workspace tools); otherwise to the global
 * {@code cmo2.alerts.slack-webhook-url}. Only {@code https://hooks.slack.com/} URLs are called.
 * With nothing configured it records intent so the pipeline still demonstrates dispatch.
 */
@Component
public class SlackAlertChannel implements AlertChannel {

    private static final Logger log = LoggerFactory.getLogger(SlackAlertChannel.class);

    private final String globalWebhook;
    private final TeamNotifier team;

    public SlackAlertChannel(@Value("${cmo2.alerts.slack-webhook-url:}") String globalWebhook, TeamNotifier team) {
        this.globalWebhook = globalWebhook;
        this.team = team;
    }

    @Override
    public ChannelType type() {
        return ChannelType.SLACK;
    }

    @Override
    public boolean send(Alert alert, String target) {
        String text = ":rotating_light: *" + alert.getSeverity() + "* — " + alert.getMessage();
        if (target != null && !target.isBlank()) {
            return post(target, text);
        }
        if (team.notify(alert.getTenantId(), text)) {
            return true;
        }
        if (globalWebhook == null || globalWebhook.isBlank()) {
            log.info("[Slack alert · demo, no webhook configured] {}", text);
            return true; // recorded intent
        }
        return post(globalWebhook, text);
    }

    private boolean post(String url, String text) {
        if (!isSlackWebhook(url)) {
            log.warn("Refusing to post a Slack alert to a non-Slack URL");
            return false;
        }
        try {
            RestClient.create().post().uri(URI.create(url.trim())).body(Map.of("text", text)).retrieve().toBodilessEntity();
            return true;
        } catch (Exception e) {
            log.warn("Slack webhook post failed: {}", e.getMessage());
            return false;
        }
    }

    private static boolean isSlackWebhook(String url) {
        try {
            URI uri = URI.create(url.trim());
            return "https".equalsIgnoreCase(uri.getScheme()) && "hooks.slack.com".equalsIgnoreCase(uri.getHost());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
