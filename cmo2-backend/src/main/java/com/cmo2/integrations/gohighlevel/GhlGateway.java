package com.cmo2.integrations.gohighlevel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriBuilder;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The only class in CMO² that talks to GoHighLevel's public API
 * ({@code services.leadconnectorhq.com}). It owns the cross-cutting rules every call needs:
 * <ul>
 *   <li>the {@code Version} header GHL requires, chosen by path;</li>
 *   <li>retries on 429/5xx for GETs only — a POST/PUT/DELETE is never replayed, because a
 *       create that timed out may already have happened;</li>
 *   <li>a local per-sub-account limiter under GHL's 100 req / 10 s and 200k / day budgets;</li>
 *   <li>an explicit {@code rateKey} (the location or agency company id) on every call, so no
 *       call can silently fall back to a default sub-account.</li>
 * </ul>
 */
@Component
public class GhlGateway {

    private static final Logger log = LoggerFactory.getLogger(GhlGateway.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static final String DEFAULT_VERSION = "2021-07-28";
    private static final int MAX_ATTEMPTS = 4;
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(30);
    private static final Duration LIMITER_MAX_WAIT = Duration.ofSeconds(15);
    private static final int MAX_PAGES = 100;

    /** Longest prefix first: /locations/search must win over a plain /locations/ rule. */
    private static final List<Map.Entry<String, String>> VERSION_BY_PREFIX = List.of(
            Map.entry("/locations/search", "v3"),
            Map.entry("/saas/", "v3"),
            Map.entry("/conversations/", "2021-04-15"),
            Map.entry("/calendars/", "2021-04-15"));

    private final RestClient client;
    private final Sleeper sleeper;
    private final Map<String, Bucket> limiters = new ConcurrentHashMap<>();
    private final Map<String, RateLimitSnapshot> lastSeenLimits = new ConcurrentHashMap<>();

    @Autowired
    public GhlGateway(@Value("${cmo2.integrations.gohighlevel.base-url:https://services.leadconnectorhq.com}") String baseUrl) {
        this(RestClient.builder().baseUrl(baseUrl), Sleeper.REAL);
    }

    GhlGateway(RestClient.Builder builder, Sleeper sleeper) {
        this.client = builder.build();
        this.sleeper = sleeper;
    }

    @FunctionalInterface
    interface Sleeper {
        Sleeper REAL = d -> Thread.sleep(d.toMillis());

        void sleep(Duration duration) throws InterruptedException;
    }

    /** What GHL last reported for a sub-account's request budget (null fields = header absent). */
    public record RateLimitSnapshot(Integer remaining, Integer dailyRemaining) {}

    public JsonNode get(String path, Map<String, ?> query, String token, String rateKey) {
        return execute(HttpMethod.GET, path, query, null, token, rateKey, true);
    }

    public JsonNode post(String path, Object body, String token, String rateKey) {
        return execute(HttpMethod.POST, path, Map.of(), body, token, rateKey, false);
    }

    public JsonNode put(String path, Object body, String token, String rateKey) {
        return execute(HttpMethod.PUT, path, Map.of(), body, token, rateKey, false);
    }

    public JsonNode delete(String path, Object body, String token, String rateKey) {
        return execute(HttpMethod.DELETE, path, Map.of(), body, token, rateKey, false);
    }

    /**
     * Total number of records a list endpoint reports. Uses {@code meta.total} from a single
     * one-row request when GHL provides it, and only falls back to paging when it doesn't.
     */
    public long count(String path, Map<String, ?> query, String arrayField, String token, String rateKey) {
        Map<String, Object> probe = new LinkedHashMap<>(query);
        probe.put("limit", 1);
        JsonNode first = get(path, probe, token, rateKey);
        JsonNode total = first == null ? null : first.path("meta").get("total");
        if (total != null && total.isNumber()) {
            return total.asLong();
        }
        return getAllPages(path, query, arrayField, token, rateKey).size();
    }

    /** Follows GHL's {@code meta.startAfterId}/{@code meta.startAfter} cursor until exhausted (capped). */
    public List<JsonNode> getAllPages(String path, Map<String, ?> query, String arrayField, String token, String rateKey) {
        return getPages(path, query, arrayField, token, rateKey, MAX_PAGES).items();
    }

    /** Items read, and whether more pages were left unread when {@code maxPages} ran out. */
    public record Pages(List<JsonNode> items, boolean truncated) {}

    public Pages getPages(String path, Map<String, ?> query, String arrayField, String token, String rateKey,
                          int maxPages) {
        List<JsonNode> all = new ArrayList<>();
        Map<String, Object> pageQuery = new LinkedHashMap<>(query);
        pageQuery.putIfAbsent("limit", 100);
        for (int page = 0; page < Math.min(maxPages, MAX_PAGES); page++) {
            JsonNode body = get(path, pageQuery, token, rateKey);
            JsonNode items = body == null ? null : body.get(arrayField);
            if (items == null || !items.isArray() || items.isEmpty()) {
                return new Pages(all, false);
            }
            items.forEach(all::add);
            JsonNode meta = body.path("meta");
            String startAfterId = meta.path("startAfterId").asText(null);
            if (startAfterId == null || startAfterId.isBlank() || "null".equals(startAfterId)) {
                return new Pages(all, false);
            }
            pageQuery.put("startAfterId", startAfterId);
            if (meta.hasNonNull("startAfter")) {
                pageQuery.put("startAfter", meta.get("startAfter").asText());
            }
        }
        return new Pages(all, true);
    }

    public RateLimitSnapshot lastRateLimit(String rateKey) {
        return lastSeenLimits.get(rateKey);
    }

    static String versionFor(String path) {
        for (Map.Entry<String, String> rule : VERSION_BY_PREFIX) {
            if (path.startsWith(rule.getKey())) {
                return rule.getValue();
            }
        }
        return DEFAULT_VERSION;
    }

    private JsonNode execute(HttpMethod method, String path, Map<String, ?> query, Object body,
                             String token, String rateKey, boolean retryable) {
        if (rateKey == null || rateKey.isBlank()) {
            throw new IllegalArgumentException("GHL calls need an explicit location/company id; none was given for " + path);
        }
        if (token == null || token.isBlank()) {
            throw new GhlApiException(401, "No GHL access token available for " + rateKey);
        }
        String version = versionFor(path);
        for (int attempt = 1; ; attempt++) {
            acquire(rateKey);
            try {
                RestClient.RequestBodySpec spec = client.method(method)
                        .uri(uri -> buildUri(uri, path, query))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header("Version", version)
                        .accept(MediaType.APPLICATION_JSON);
                if (body != null) {
                    spec.contentType(MediaType.APPLICATION_JSON).body(body);
                }
                return spec.<JsonNode>exchange((request, response) -> {
                    recordRateLimits(rateKey, response.getHeaders());
                    byte[] bytes = response.getBody().readAllBytes();
                    if (response.getStatusCode().isError()) {
                        throw new RestClientResponseException(
                                "GHL " + method + " " + path + " failed",
                                response.getStatusCode(), response.getStatusText(),
                                response.getHeaders(), bytes, null);
                    }
                    return bytes.length == 0 ? null : MAPPER.readTree(bytes);
                });
            } catch (RestClientResponseException e) {
                int status = e.getStatusCode().value();
                if (retryable && isRetryable(status) && attempt < MAX_ATTEMPTS) {
                    pause(backoff(attempt, e.getResponseHeaders()), path);
                    continue;
                }
                throw new GhlApiException(status, describe(method, path, status, e.getResponseBodyAsString()));
            } catch (GhlApiException e) {
                throw e;
            } catch (Exception e) {
                if (retryable && attempt < MAX_ATTEMPTS) {
                    pause(backoff(attempt, null), path);
                    continue;
                }
                throw new GhlApiException(0, "Could not reach GoHighLevel (" + method + " " + path + "): " + e.getMessage());
            }
        }
    }

    private URI buildUri(UriBuilder uri, String path, Map<String, ?> query) {
        uri.path(path);
        query.forEach((k, v) -> {
            if (v != null) uri.queryParam(k, v);
        });
        return uri.build();
    }

    private static boolean isRetryable(int status) {
        return status == 429 || status == 500 || status == 502 || status == 503 || status == 504;
    }

    static Duration backoff(int attempt, HttpHeaders headers) {
        String retryAfter = headers == null ? null : headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (retryAfter != null) {
            try {
                Duration requested = Duration.ofSeconds(Long.parseLong(retryAfter.trim()));
                return requested.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : requested;
            } catch (NumberFormatException ignored) {
                // HTTP-date form: fall back to exponential backoff.
            }
        }
        Duration exponential = Duration.ofSeconds(1L << (attempt - 1));
        return exponential.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : exponential;
    }

    private void pause(Duration duration, String path) {
        try {
            sleeper.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GhlApiException(0, "Interrupted while retrying " + path);
        }
    }

    private void acquire(String rateKey) {
        Bucket bucket = limiters.computeIfAbsent(rateKey, k -> Bucket.builder()
                .addLimit(Bandwidth.builder().capacity(100).refillIntervally(100, Duration.ofSeconds(10)).build())
                .addLimit(Bandwidth.builder().capacity(200_000).refillIntervally(200_000, Duration.ofDays(1)).build())
                .build());
        try {
            if (!bucket.asBlocking().tryConsume(1, LIMITER_MAX_WAIT)) {
                throw new GhlApiException(429, "Local GHL request budget exhausted for " + rateKey);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GhlApiException(0, "Interrupted while waiting for GHL request budget");
        }
    }

    private void recordRateLimits(String rateKey, HttpHeaders headers) {
        Integer remaining = intHeader(headers, "X-RateLimit-Remaining");
        Integer daily = intHeader(headers, "X-RateLimit-Daily-Remaining");
        if (remaining == null && daily == null) {
            return;
        }
        lastSeenLimits.put(rateKey, new RateLimitSnapshot(remaining, daily));
        if (daily != null && daily < 1_000) {
            log.warn("GHL daily request budget for {} is nearly spent: {} left", rateKey, daily);
        }
    }

    private static Integer intHeader(HttpHeaders headers, String name) {
        String v = headers.getFirst(name);
        if (v == null) return null;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String describe(HttpMethod method, String path, int status, String body) {
        String snippet = body == null ? "" : body.replaceAll("\\s+", " ");
        if (snippet.length() > 300) snippet = snippet.substring(0, 300) + "…";
        return "GHL " + method + " " + path + " returned " + status + (snippet.isBlank() ? "" : ": " + snippet);
    }
}
