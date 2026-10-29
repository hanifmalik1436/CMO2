package com.cmo2.integrations.gohighlevel;

import com.cmo2.integrations.core.Metrics;
import com.cmo2.integrations.core.Timezones;
import com.cmo2.integrations.core.SyncModels.CustomerFacts;
import com.cmo2.integrations.core.SyncModels.KpiPoint;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads one sub-account's business figures (tier-0, read-only) through {@link GhlGateway}.
 *
 * <p>Each figure is fetched on its own: if GHL refuses one endpoint (a missing scope, an
 * unavailable feature) the others still arrive and the failure is named in {@link Snapshot#failed()}.
 * Only running totals and "today" figures are read; daily new leads and deals won are derived later
 * from how much the totals grew, so CMO² never depends on GHL's date filters for them.
 *
 * <p>"Today" is the sub-account's own day, in its GHL timezone.
 */
@Component
public class GhlLocationReader {

    private static final Logger log = LoggerFactory.getLogger(GhlLocationReader.class);

    static final int MAX_OPPORTUNITY_PAGES = 10;
    static final int MAX_TRANSACTION_PAGES = 10;
    static final int MAX_CALENDARS = 20;
    private static final int PAGE_SIZE = 100;
    private static final Set<String> NOT_BOOKED = Set.of("cancelled", "canceled", "invalid");

    private final GhlGateway gateway;
    private final Clock clock;

    @Autowired
    public GhlLocationReader(GhlGateway gateway) {
        this(gateway, Clock.systemUTC());
    }

    GhlLocationReader(GhlGateway gateway, Clock clock) {
        this.gateway = gateway;
        this.clock = clock;
    }

    /**
     * @param kpis    raw figures that could be read
     * @param facts   the sub-account's own details, or null if they couldn't be read
     * @param failed  figures GHL refused, with a short reason each
     * @param notes   caveats about figures that were read (e.g. capped paging)
     * @param tokenRejected true when GHL rejected the access token outright
     */
    public record Snapshot(List<KpiPoint> kpis, CustomerFacts facts, Map<String, String> failed,
                           List<String> notes, boolean tokenRejected) {}

    public Snapshot read(String locationId, String token, String fallbackTimezone) {
        List<KpiPoint> kpis = new ArrayList<>();
        Map<String, String> failed = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();

        CustomerFacts facts = null;
        try {
            facts = facts(locationId, token);
        } catch (GhlApiException e) {
            if (e.isUnauthorized()) {
                return new Snapshot(List.of(), null, Map.of("location", e.getMessage()), List.of(), true);
            }
            failed.put("business details", reason(e));
        }

        ZoneId zone = Timezones.firstValid(facts == null ? null : facts.timezone(), fallbackTimezone);
        LocalDate today = LocalDate.now(clock.withZone(zone));
        Instant dayStart = today.atStartOfDay(zone).toInstant();
        Instant dayEnd = today.plusDays(1).atStartOfDay(zone).toInstant();

        attempt("contacts", failed, () -> kpis.add(new KpiPoint(Metrics.CONTACTS_TOTAL,
                gateway.count("/contacts/", Map.of("locationId", locationId), "contacts", token, locationId))));

        Long[] wonLost = new Long[2];
        attempt("opportunities", failed, () -> {
            readOpenPipeline(locationId, token, kpis, notes);
            wonLost[0] = opportunityCount(locationId, "won", token);
            wonLost[1] = opportunityCount(locationId, "lost", token);
            kpis.add(new KpiPoint(Metrics.OPPORTUNITIES_WON_TOTAL, wonLost[0]));
            kpis.add(new KpiPoint(Metrics.OPPORTUNITIES_LOST_TOTAL, wonLost[1]));
            long closed = wonLost[0] + wonLost[1];
            kpis.add(new KpiPoint(Metrics.CONVERSION_RATE, closed == 0 ? 0 : round(100.0 * wonLost[0] / closed)));
        });

        attempt("revenue", failed, () -> kpis.add(new KpiPoint(Metrics.REVENUE,
                revenueToday(locationId, token, dayStart, dayEnd, notes))));

        attempt("appointments", failed, () -> kpis.add(new KpiPoint(Metrics.APPOINTMENTS,
                appointmentsToday(locationId, token, dayStart, dayEnd, notes))));

        attempt("conversations", failed, () -> kpis.add(new KpiPoint(Metrics.CONVERSATIONS_UNREAD,
                unreadConversations(locationId, token))));

        List<String> channels = new ArrayList<>();
        boolean[] socialRead = {false};
        attempt("social accounts", failed, () -> {
            channels.addAll(socialChannels(locationId, token));
            kpis.add(new KpiPoint(Metrics.SOCIAL_ACCOUNTS, channels.size()));
            socialRead[0] = true;
        });
        if (facts != null && socialRead[0]) {
            facts = new CustomerFacts(facts.businessName(), facts.website(), facts.timezone(), facts.city(),
                    facts.country(), facts.phone(), facts.email(), List.copyOf(new LinkedHashSet<>(channels)));
        }
        return new Snapshot(kpis, facts, failed, notes, false);
    }

    // ---- business details ------------------------------------------------------------------

    CustomerFacts facts(String locationId, String token) {
        JsonNode body = gateway.get("/locations/" + locationId, Map.of(), token, locationId);
        JsonNode loc = body == null ? null : body.path("location");
        if (loc == null || loc.isMissingNode() || loc.isNull()) {
            loc = body;
        }
        JsonNode biz = loc == null ? null : loc.path("business");
        return new CustomerFacts(
                first(loc, biz, "name"),
                first(loc, biz, "website"),
                first(loc, biz, "timezone"),
                first(loc, biz, "city"),
                first(loc, biz, "country"),
                first(loc, biz, "phone"),
                first(loc, biz, "email"),
                null);
    }

    // ---- opportunities ---------------------------------------------------------------------

    private void readOpenPipeline(String locationId, String token, List<KpiPoint> kpis, List<String> notes) {
        GhlGateway.Pages open = gateway.getPages("/opportunities/search",
                Map.of("location_id", locationId, "status", "open", "limit", PAGE_SIZE),
                "opportunities", token, locationId, MAX_OPPORTUNITY_PAGES);
        double value = 0;
        for (JsonNode o : open.items()) {
            value += o.path("monetaryValue").asDouble(0);
        }
        long openCount = open.truncated() ? opportunityCount(locationId, "open", token) : open.items().size();
        if (open.truncated()) {
            notes.add("pipeline value covers the first " + open.items().size() + " of " + openCount
                    + " open opportunities");
        }
        kpis.add(new KpiPoint(Metrics.OPPORTUNITIES_OPEN, openCount));
        kpis.add(new KpiPoint(Metrics.PIPELINE_VALUE, round(value)));
    }

    private long opportunityCount(String locationId, String status, String token) {
        // /opportunities/search takes snake_case location_id on API version 2021-07-28.
        return gateway.count("/opportunities/search", Map.of("location_id", locationId, "status", status),
                "opportunities", token, locationId);
    }

    // ---- payments --------------------------------------------------------------------------

    /**
     * Sum of today's successful, live-mode transactions. GHL documents startAt/endAt without a
     * format, so the window is also enforced here on each transaction's createdAt. Amounts are
     * summed as GHL returns them (the currency's major unit, unverified against a live account).
     */
    private double revenueToday(String locationId, String token, Instant dayStart, Instant dayEnd,
                                List<String> notes) {
        double total = 0;
        Set<String> currencies = new LinkedHashSet<>();
        for (int page = 0; page < MAX_TRANSACTION_PAGES; page++) {
            Map<String, Object> query = new LinkedHashMap<>();
            query.put("altId", locationId);
            query.put("altType", "location");
            query.put("startAt", dayStart.toString());
            query.put("endAt", dayEnd.toString());
            query.put("limit", PAGE_SIZE);
            query.put("offset", page * PAGE_SIZE);
            JsonNode body = gateway.get("/payments/transactions", query, token, locationId);
            JsonNode data = body == null ? null : body.get("data");
            if (data == null || !data.isArray() || data.isEmpty()) {
                break;
            }
            for (JsonNode t : data) {
                if (!"succeeded".equalsIgnoreCase(t.path("status").asText())) continue;
                if (t.has("liveMode") && !t.path("liveMode").asBoolean(true)) continue;
                Instant created = instant(t.path("createdAt").asText(null));
                if (created == null || created.isBefore(dayStart) || !created.isBefore(dayEnd)) continue;
                total += t.path("amount").asDouble(0);
                String currency = t.path("currency").asText("");
                if (!currency.isBlank()) currencies.add(currency.toUpperCase());
            }
            if (data.size() < PAGE_SIZE) {
                break;
            }
            if (page == MAX_TRANSACTION_PAGES - 1) {
                notes.add("revenue covers the first " + MAX_TRANSACTION_PAGES * PAGE_SIZE + " transactions today");
            }
        }
        if (currencies.size() > 1) {
            notes.add("revenue mixes currencies " + String.join(", ", currencies));
        }
        return round(total);
    }

    // ---- calendars -------------------------------------------------------------------------

    /** Booked (not cancelled) appointments today across the sub-account's calendars. */
    private long appointmentsToday(String locationId, String token, Instant dayStart, Instant dayEnd,
                                   List<String> notes) {
        JsonNode body = gateway.get("/calendars/", Map.of("locationId", locationId), token, locationId);
        JsonNode calendars = body == null ? null : body.get("calendars");
        if (calendars == null || !calendars.isArray()) {
            return 0;
        }
        List<String> ids = new ArrayList<>();
        for (JsonNode c : calendars) {
            if (c.has("isActive") && !c.path("isActive").asBoolean(true)) continue;
            String id = c.path("id").asText("");
            if (!id.isBlank()) ids.add(id);
        }
        if (ids.size() > MAX_CALENDARS) {
            notes.add("appointments cover " + MAX_CALENDARS + " of " + ids.size() + " calendars");
            ids = ids.subList(0, MAX_CALENDARS);
        }
        long booked = 0;
        for (String calendarId : ids) {
            JsonNode events = gateway.get("/calendars/events", Map.of(
                    "locationId", locationId,
                    "calendarId", calendarId,
                    "startTime", dayStart.toEpochMilli(),
                    "endTime", dayEnd.toEpochMilli() - 1), token, locationId).path("events");
            if (!events.isArray()) continue;
            for (JsonNode e : events) {
                if (e.path("deleted").asBoolean(false)) continue;
                if (NOT_BOOKED.contains(e.path("appointmentStatus").asText("").toLowerCase())) continue;
                booked++;
            }
        }
        return booked;
    }

    // ---- conversations / social -------------------------------------------------------------

    private long unreadConversations(String locationId, String token) {
        JsonNode body = gateway.get("/conversations/search",
                Map.of("locationId", locationId, "status", "unread", "limit", 1), token, locationId);
        if (body == null) return 0;
        if (body.path("total").isNumber()) return body.path("total").asLong();
        JsonNode list = body.path("conversations");
        return list.isArray() ? list.size() : 0;
    }

    /** Channel names ("facebook", "instagram"…) of the social accounts connected in the sub-account. */
    private List<String> socialChannels(String locationId, String token) {
        JsonNode body = gateway.get("/social-media-posting/" + locationId + "/accounts", Map.of(), token, locationId);
        JsonNode accounts = firstArray(body,
                List.of("results", "accounts"), List.of("accounts"), List.of("results"), List.of("data"));
        List<String> out = new ArrayList<>();
        if (accounts == null) return out;
        for (JsonNode a : accounts) {
            if (a.path("deleted").asBoolean(false) || a.path("isExpired").asBoolean(false)) continue;
            String platform = a.path("platform").asText(a.path("type").asText(""));
            out.add(platform.isBlank() ? "unknown" : platform.toLowerCase());
        }
        return out;
    }

    // ---- helpers ---------------------------------------------------------------------------

    private interface Fetch {
        void run();
    }

    private void attempt(String figure, Map<String, String> failed, Fetch fetch) {
        try {
            fetch.run();
        } catch (GhlApiException e) {
            log.info("GHL figure '{}' unavailable: {}", figure, e.getMessage());
            failed.put(figure, reason(e));
        } catch (RuntimeException e) {
            log.warn("GHL figure '{}' could not be parsed: {}", figure, e.toString());
            failed.put(figure, "unexpected response");
        }
    }

    private static String reason(GhlApiException e) {
        return switch (e.status()) {
            case 401, 403 -> "not permitted (check the app's scopes)";
            case 404 -> "not found";
            case 422, 400 -> "GHL rejected the request";
            default -> "GHL unavailable";
        };
    }

    private static String first(JsonNode primary, JsonNode secondary, String field) {
        for (JsonNode n : new JsonNode[] {primary, secondary}) {
            if (n == null || n.isMissingNode()) continue;
            String v = n.path(field).asText("");
            if (!v.isBlank()) return v.trim();
        }
        return null;
    }

    @SafeVarargs
    private static JsonNode firstArray(JsonNode body, List<String>... paths) {
        if (body == null) return null;
        if (body.isArray()) return body;
        for (List<String> path : paths) {
            JsonNode n = body;
            for (String p : path) n = n.path(p);
            if (n.isArray()) return n;
        }
        return null;
    }

    private static Instant instant(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return Instant.parse(iso);
        } catch (DateTimeException e) {
            try {
                return java.time.OffsetDateTime.parse(iso).toInstant();
            } catch (DateTimeException ignored) {
                return null;
            }
        }
    }

    private static double round(double v) {
        return Math.round(Math.max(0, v) * 100.0) / 100.0;
    }
}
