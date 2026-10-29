package com.cmo2.docs.client;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.HtmlUtils;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Confluence Cloud, REST API v2, with an email + API token (Basic auth). Calls go only to
 * {@code https://*.atlassian.net}, so a stored site URL can never point CMO² at another host.
 *
 * <p>Not yet exercised against a live site: the create-page body and response shape below follow
 * the v2 reference and should be confirmed the first time a real space is connected.
 */
@Component
public class ConfluenceClient {

    private static final Pattern SPACE_KEY = Pattern.compile("^[A-Za-z0-9~]{1,255}$");

    private final RestClient client;

    public ConfluenceClient() {
        this(RestClient.builder());
    }

    ConfluenceClient(RestClient.Builder builder) {
        this.client = builder.build();
    }

    public record ConfluenceAuth(String siteUrl, String email, String apiToken) {}

    public record ConfluenceSpace(String id, String key, String name, String homepageId) {}

    public record ConfluencePage(String id, String url) {}

    /** Returns {@code https://<host>} for an Atlassian Cloud site, rejecting anything else. */
    public static String normalizeSite(String input) {
        if (input == null || input.isBlank()) {
            throw new DocClientException("Confluence site URL is required (e.g. https://your-team.atlassian.net)");
        }
        String s = input.trim();
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://" + s;
        URI uri;
        try {
            uri = URI.create(s);
        } catch (IllegalArgumentException e) {
            throw new DocClientException("That isn't a valid Confluence site URL");
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
        if (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme())) {
            throw new DocClientException("That isn't a valid Confluence site URL");
        }
        if (!host.matches("^[a-z0-9][a-z0-9-]*\\.atlassian\\.net$")) {
            throw new DocClientException("Only Atlassian Cloud sites (https://<name>.atlassian.net) are supported");
        }
        return "https://" + host;
    }

    public static String requireSpaceKey(String key) {
        if (key == null || !SPACE_KEY.matcher(key.trim()).matches()) {
            throw new DocClientException("That isn't a valid Confluence space key");
        }
        return key.trim();
    }

    public ConfluenceSpace space(ConfluenceAuth auth, String spaceKey) {
        String site = normalizeSite(auth.siteUrl());
        JsonNode body = call(() -> client.get()
                .uri(site + "/wiki/api/v2/spaces?keys={key}", requireSpaceKey(spaceKey))
                .headers(h -> headers(h, auth)).retrieve().body(JsonNode.class));
        JsonNode results = body == null ? null : body.path("results");
        if (results == null || !results.isArray() || results.isEmpty()) {
            throw new DocClientException("No Confluence space with key '" + spaceKey + "' is visible to this account");
        }
        JsonNode s = results.get(0);
        return new ConfluenceSpace(text(s, "id"), text(s, "key"), text(s, "name"), text(s, "homepageId"));
    }

    /** Creates a page in the space, under {@code parentId} if given. Titles must be unique within a space. */
    public ConfluencePage createPage(ConfluenceAuth auth, String spaceId, String parentId, String title, String body) {
        String site = normalizeSite(auth.siteUrl());
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("spaceId", spaceId);
        request.put("status", "current");
        request.put("title", title.length() > 255 ? title.substring(0, 255) : title);
        if (parentId != null && !parentId.isBlank()) request.put("parentId", parentId);
        request.put("body", Map.of("representation", "storage", "value", toStorage(body)));

        JsonNode created = call(() -> client.post().uri(site + "/wiki/api/v2/pages")
                .headers(h -> headers(h, auth)).contentType(MediaType.APPLICATION_JSON)
                .body(request).retrieve().body(JsonNode.class));
        String id = created == null ? null : text(created, "id");
        if (id == null) {
            throw new DocClientException("Confluence did not return the new page's id");
        }
        String webui = created.path("_links").path("webui").asText(null);
        return new ConfluencePage(id, webui == null ? null : site + "/wiki" + webui);
    }

    /** A page's current text, one line per paragraph line or heading (to see what the owner changed). */
    public String pageText(ConfluenceAuth auth, String pageId) {
        String site = normalizeSite(auth.siteUrl());
        JsonNode page = call(() -> client.get().uri(site + "/wiki/api/v2/pages/{id}?body-format=storage", pageId)
                .headers(h -> headers(h, auth)).retrieve().body(JsonNode.class));
        String storage = page == null ? "" : page.path("body").path("storage").path("value").asText("");
        return fromStorage(storage);
    }

    /** Storage format back to plain lines: block ends and line breaks become newlines, tags are dropped. */
    static String fromStorage(String storage) {
        String text = storage
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</(p|h[1-6]|li|div|tr|blockquote)>", "\n")
                .replaceAll("<[^>]+>", "");
        return HtmlUtils.htmlUnescape(text).replace("\u00a0", " ").trim();
    }

    /** Plain text to Confluence storage format: "# " lines become headings, the rest escaped paragraphs. */
    static String toStorage(String body) {
        if (body == null || body.isBlank()) return "<p></p>";
        StringBuilder out = new StringBuilder();
        for (String para : body.trim().split("\\n\\s*\\n")) {
            String p = para.strip();
            if (p.isEmpty()) continue;
            if (p.startsWith("# ")) {
                out.append("<h2>").append(HtmlUtils.htmlEscape(p.substring(2).strip())).append("</h2>");
            } else {
                out.append("<p>").append(HtmlUtils.htmlEscape(p).replace("\n", "<br/>")).append("</p>");
            }
        }
        return out.toString();
    }

    private static void headers(HttpHeaders h, ConfluenceAuth auth) {
        String basic = Base64.getEncoder().encodeToString(
                (auth.email() + ":" + auth.apiToken()).getBytes(StandardCharsets.UTF_8));
        h.set(HttpHeaders.AUTHORIZATION, "Basic " + basic);
        h.setAccept(List.of(MediaType.APPLICATION_JSON));
    }

    private static <T> T call(java.util.function.Supplier<T> request) {
        try {
            return request.get();
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            String why = status == 401 ? "the email or API token was rejected"
                    : status == 403 ? "this account can't create pages in that space"
                    : trim(e.getResponseBodyAsString());
            throw new DocClientException("Confluence returned " + status + ": " + why);
        } catch (DocClientException e) {
            throw e;
        } catch (Exception e) {
            throw new DocClientException("Could not reach Confluence: " + e.getMessage());
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static String trim(String s) {
        if (s == null) return "";
        Matcher m = Pattern.compile("\"(?:message|title)\"\\s*:\\s*\"([^\"]{0,300})").matcher(s);
        if (m.find()) return m.group(1);
        String flat = s.replaceAll("\\s+", " ");
        return flat.length() > 300 ? flat.substring(0, 300) + "…" : flat;
    }
}
