package com.cmo2.docs.client;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.util.Map;

/**
 * Slack incoming webhooks. Only {@code https://hooks.slack.com/...} URLs are ever called, so a
 * stored webhook can't be used to make CMO² request an arbitrary address.
 */
@Component
public class SlackClient {

    private final RestClient client;

    public SlackClient() {
        this(RestClient.builder());
    }

    SlackClient(RestClient.Builder builder) {
        this.client = builder.build();
    }

    public static boolean isSlackWebhook(String url) {
        if (url == null) return false;
        try {
            URI uri = URI.create(url.trim());
            return "https".equalsIgnoreCase(uri.getScheme())
                    && "hooks.slack.com".equalsIgnoreCase(uri.getHost())
                    && uri.getPath() != null && uri.getPath().startsWith("/services/");
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public void post(String webhookUrl, String text) {
        if (!isSlackWebhook(webhookUrl)) {
            throw new DocClientException("Slack webhook URLs must start with https://hooks.slack.com/services/");
        }
        try {
            client.post().uri(URI.create(webhookUrl.trim()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("text", text))
                    .retrieve().toBodilessEntity();
        } catch (RestClientResponseException e) {
            throw new DocClientException("Slack returned " + e.getStatusCode().value() + ": " + e.getResponseBodyAsString());
        } catch (Exception e) {
            throw new DocClientException("Could not reach Slack: " + e.getMessage());
        }
    }
}
