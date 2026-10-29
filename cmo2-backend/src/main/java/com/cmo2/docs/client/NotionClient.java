package com.cmo2.docs.client;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Notion, pinned to API version 2022-06-28. Newer versions split databases into "data sources";
 * the pinned version keeps the simpler database model and is still served (checked against a
 * real workspace). Long content is split to respect Notion's limits: 2,000 characters per text
 * object and 100 blocks per request.
 */
@Component
public class NotionClient {

    static final String VERSION = "2022-06-28";
    static final int MAX_TEXT = 2000;
    static final int MAX_BLOCKS = 100;
    private static final Pattern DASHED_ID = Pattern.compile(
            "(?<![0-9a-fA-F])([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})(?![0-9a-fA-F])");
    private static final Pattern PLAIN_ID = Pattern.compile("(?<![0-9a-fA-F])([0-9a-fA-F]{32})(?![0-9a-fA-F])");

    private final RestClient client;

    @Autowired
    public NotionClient(@Value("${cmo2.docs.notion-base-url:https://api.notion.com}") String baseUrl) {
        this(RestClient.builder().baseUrl(baseUrl));
    }

    NotionClient(RestClient.Builder builder) {
        this.client = builder.build();
    }

    /** Where customer pages go: a page, or a database (then {@code titleProperty} names its title column). */
    public record NotionParent(String id, String kind, String titleProperty, String url) {
        public static NotionParent page(String id) {
            return new NotionParent(id, "page", "title", null);
        }
    }

    public record NotionPage(String id, String url) {}

    /** Extracts the 32-hex Notion id from an id (with or without dashes) or a pasted Notion URL. */
    public static String normalizeId(String input) {
        if (input == null) {
            throw new DocClientException("Notion page or database id is required");
        }
        String s = input.trim();
        int query = s.indexOf('?');
        if (query >= 0) s = s.substring(0, query);
        // Match on the original text: stripping dashes first would glue a page title's last
        // letter (e.g. "...-Title-<id>") onto the id and shift it by one character.
        String last = lastMatch(DASHED_ID, s);
        if (last == null) last = lastMatch(PLAIN_ID, s);
        if (last == null) {
            throw new DocClientException("That doesn't look like a Notion page or database id");
        }
        return last.replace("-", "").toLowerCase();
    }

    private static String lastMatch(Pattern p, String s) {
        Matcher m = p.matcher(s);
        String last = null;
        while (m.find()) last = m.group(1);
        return last;
    }

    /** The integration's bot name — proves the token works. */
    public String botName(String token) {
        JsonNode me = call(() -> client.get().uri("/v1/users/me").headers(h -> auth(h, token))
                .retrieve().body(JsonNode.class));
        return me == null ? null : text(me, "name");
    }

    /** Resolves a root id as a database (preferred) or a page the integration can see. */
    public NotionParent describeRoot(String token, String rootId) {
        try {
            JsonNode db = client.get().uri("/v1/databases/{id}", rootId).headers(h -> auth(h, token))
                    .retrieve().body(JsonNode.class);
            String titleProperty = null;
            JsonNode props = db == null ? null : db.path("properties");
            if (props != null) {
                var it = props.fields();
                while (it.hasNext()) {
                    var e = it.next();
                    if ("title".equals(e.getValue().path("type").asText())) {
                        titleProperty = e.getKey();
                        break;
                    }
                }
            }
            return new NotionParent(rootId, "database", titleProperty == null ? "Name" : titleProperty,
                    db == null ? null : text(db, "url"));
        } catch (RestClientResponseException notADatabase) {
            if (notADatabase.getStatusCode().value() == 401) {
                throw new DocClientException("Notion rejected the token");
            }
        }
        JsonNode page = call(() -> client.get().uri("/v1/pages/{id}", rootId).headers(h -> auth(h, token))
                .retrieve().body(JsonNode.class));
        return new NotionParent(rootId, "page", "title", page == null ? null : text(page, "url"));
    }

    /** Creates a page under {@code parent}, with {@code body} split into blocks. */
    public NotionPage createPage(String token, NotionParent parent, String title, String body) {
        List<Map<String, Object>> blocks = blocks(body);
        List<Map<String, Object>> first = blocks.subList(0, Math.min(MAX_BLOCKS, blocks.size()));

        Map<String, Object> request = new LinkedHashMap<>();
        boolean database = "database".equals(parent.kind());
        request.put("parent", Map.of(database ? "database_id" : "page_id", parent.id()));
        request.put("properties", Map.of(database ? parent.titleProperty() : "title",
                Map.of("title", List.of(textObject(truncate(title, MAX_TEXT))))));
        request.put("children", first);

        JsonNode created = call(() -> client.post().uri("/v1/pages").headers(h -> auth(h, token))
                .contentType(MediaType.APPLICATION_JSON).body(request).retrieve().body(JsonNode.class));
        String id = created == null ? null : text(created, "id");
        if (id == null) {
            throw new DocClientException("Notion did not return the new page's id");
        }
        for (int i = MAX_BLOCKS; i < blocks.size(); i += MAX_BLOCKS) {
            List<Map<String, Object>> chunk = blocks.subList(i, Math.min(i + MAX_BLOCKS, blocks.size()));
            call(() -> client.patch().uri("/v1/blocks/{id}/children", id).headers(h -> auth(h, token))
                    .contentType(MediaType.APPLICATION_JSON).body(Map.of("children", chunk))
                    .retrieve().body(JsonNode.class));
        }
        return new NotionPage(id, text(created, "url"));
    }

    static final int MAX_READ_PAGES = 5;

    /**
     * A page's current text: one line per line of each text block (paragraphs, headings, list
     * items, to-dos, quotes, callouts), reading at most {@value #MAX_READ_PAGES} pages of children.
     */
    public String pageText(String token, String pageId) {
        StringBuilder out = new StringBuilder();
        String cursor = null;
        for (int i = 0; i < MAX_READ_PAGES; i++) {
            String after = cursor;
            JsonNode page = call(() -> client.get()
                    .uri(u -> {
                        u.path("/v1/blocks/{id}/children").queryParam("page_size", MAX_BLOCKS);
                        if (after != null) u.queryParam("start_cursor", after);
                        return u.build(pageId);
                    })
                    .headers(h -> auth(h, token)).retrieve().body(JsonNode.class));
            if (page == null) break;
            for (JsonNode block : page.path("results")) {
                JsonNode rich = block.path(block.path("type").asText()).path("rich_text");
                if (!rich.isArray()) continue;
                StringBuilder line = new StringBuilder();
                rich.forEach(r -> line.append(r.path("plain_text").asText(r.path("text").path("content").asText(""))));
                if (!line.isEmpty()) out.append(line).append('\n');
            }
            if (!page.path("has_more").asBoolean(false)) break;
            cursor = page.path("next_cursor").asText(null);
            if (cursor == null) break;
        }
        return out.toString().trim();
    }

    /** Lines starting with "# " become headings; every other paragraph becomes ≤2,000-char paragraph blocks. */
    static List<Map<String, Object>> blocks(String body) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (body == null || body.isBlank()) {
            return out;
        }
        for (String para : body.trim().split("\\n\\s*\\n")) {
            String p = para.strip();
            if (p.isEmpty()) continue;
            if (p.startsWith("# ")) {
                out.add(block("heading_2", truncate(p.substring(2).strip(), MAX_TEXT)));
                continue;
            }
            for (int i = 0; i < p.length(); i += MAX_TEXT) {
                out.add(block("paragraph", p.substring(i, Math.min(i + MAX_TEXT, p.length()))));
            }
        }
        return out;
    }

    private static Map<String, Object> block(String type, String content) {
        return Map.of("object", "block", "type", type, type, Map.of("rich_text", List.of(textObject(content))));
    }

    private static Map<String, Object> textObject(String content) {
        return Map.of("type", "text", "text", Map.of("content", content));
    }

    private static void auth(HttpHeaders h, String token) {
        h.setBearerAuth(token);
        h.set("Notion-Version", VERSION);
        h.setAccept(List.of(MediaType.APPLICATION_JSON));
    }

    private static <T> T call(java.util.function.Supplier<T> request) {
        try {
            return request.get();
        } catch (RestClientResponseException e) {
            throw new DocClientException("Notion returned " + e.getStatusCode().value() + ": " + message(e));
        } catch (DocClientException e) {
            throw e;
        } catch (Exception e) {
            throw new DocClientException("Could not reach Notion: " + e.getMessage());
        }
    }

    private static String message(RestClientResponseException e) {
        String body = e.getResponseBodyAsString();
        Matcher m = Pattern.compile("\"message\"\\s*:\\s*\"([^\"]{0,300})").matcher(body == null ? "" : body);
        if (m.find()) return m.group(1);
        if (e.getStatusCode().value() == 404) return "not found, or not shared with your integration";
        return e.getStatusText();
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
