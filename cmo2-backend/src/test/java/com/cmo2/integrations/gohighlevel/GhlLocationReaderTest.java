package com.cmo2.integrations.gohighlevel;

import com.cmo2.integrations.core.Metrics;
import com.cmo2.integrations.core.SyncModels.KpiPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GhlLocationReaderTest {

    private static final String BASE = "https://ghl.test";
    private static final String LOC = "loc1";
    // 2026-10-28 15:00 UTC = 11:00 in New York (EDT, UTC-4).
    private static final Instant NOW = Instant.parse("2026-10-28T15:00:00Z");
    private static final long NY_DAY_START = Instant.parse("2026-10-28T04:00:00Z").toEpochMilli();

    private MockRestServiceServer server;
    private GhlLocationReader reader;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        reader = new GhlLocationReader(new GhlGateway(builder, d -> {}), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void respond(String path, String json) {
        server.expect(ExpectedCount.manyTimes(), requestTo(startsWith(BASE + path)))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
    }

    private void location() {
        respond("/locations/" + LOC, """
                {"location":{"name":"Acme Roofing","website":"https://acme.test","timezone":"America/New_York",
                 "city":"Albany","country":"US","phone":"+15550100","email":"hi@acme.test"}}""");
    }

    private void opportunities() {
        server.expect(ExpectedCount.manyTimes(), requestTo(startsWith(BASE + "/opportunities/search")))
                .andExpect(queryParam("status", "open"))
                .andRespond(withSuccess("""
                        {"opportunities":[{"monetaryValue":1500},{"monetaryValue":2500.5}],"meta":{"total":2}}""",
                        MediaType.APPLICATION_JSON));
        server.expect(ExpectedCount.manyTimes(), requestTo(startsWith(BASE + "/opportunities/search")))
                .andExpect(queryParam("status", "won"))
                .andRespond(withSuccess("{\"opportunities\":[{}],\"meta\":{\"total\":30}}", MediaType.APPLICATION_JSON));
        server.expect(ExpectedCount.manyTimes(), requestTo(startsWith(BASE + "/opportunities/search")))
                .andExpect(queryParam("status", "lost"))
                .andRespond(withSuccess("{\"opportunities\":[{}],\"meta\":{\"total\":70}}", MediaType.APPLICATION_JSON));
    }

    private void calendars() {
        respond("/calendars/events", """
                {"events":[{"appointmentStatus":"confirmed"},{"appointmentStatus":"cancelled"},
                           {"appointmentStatus":"showed"},{"appointmentStatus":"new","deleted":true}]}""");
        respond("/calendars/?", """
                {"calendars":[{"id":"cal1","isActive":true},{"id":"cal2","isActive":false}]}""");
    }

    private static Map<String, Double> byMetric(GhlLocationReader.Snapshot s) {
        return s.kpis().stream().collect(Collectors.toMap(KpiPoint::metric, KpiPoint::value));
    }

    @Test
    void readsEveryFigureAndTheBusinessDetails() {
        location();
        respond("/contacts/", "{\"contacts\":[{}],\"meta\":{\"total\":4213}}");
        opportunities();
        respond("/payments/transactions", """
                {"data":[
                  {"status":"succeeded","liveMode":true,"amount":100.5,"currency":"usd","createdAt":"2026-10-28T13:00:00.000Z"},
                  {"status":"failed","liveMode":true,"amount":999,"createdAt":"2026-10-28T13:00:00Z"},
                  {"status":"succeeded","liveMode":false,"amount":999,"createdAt":"2026-10-28T13:00:00Z"},
                  {"status":"succeeded","liveMode":true,"amount":999,"createdAt":"2026-10-28T03:59:00Z"},
                  {"status":"succeeded","liveMode":true,"amount":49.5,"currency":"usd","createdAt":"2026-10-28T14:00:00Z"}
                ],"totalCount":5}""");
        calendars();
        respond("/conversations/search", "{\"conversations\":[{}],\"total\":7}");
        respond("/social-media-posting/" + LOC + "/accounts", """
                {"results":{"accounts":[{"platform":"facebook"},{"platform":"instagram"},{"platform":"google","isExpired":true}]}}""");

        GhlLocationReader.Snapshot snap = reader.read(LOC, "tok", null);

        assertThat(snap.failed()).isEmpty();
        assertThat(snap.tokenRejected()).isFalse();
        Map<String, Double> m = byMetric(snap);
        assertThat(m.get(Metrics.CONTACTS_TOTAL)).isEqualTo(4213);
        assertThat(m.get(Metrics.OPPORTUNITIES_OPEN)).isEqualTo(2);
        assertThat(m.get(Metrics.PIPELINE_VALUE)).isEqualTo(4000.5);
        assertThat(m.get(Metrics.OPPORTUNITIES_WON_TOTAL)).isEqualTo(30);
        assertThat(m.get(Metrics.OPPORTUNITIES_LOST_TOTAL)).isEqualTo(70);
        assertThat(m.get(Metrics.CONVERSION_RATE)).isEqualTo(30.0);
        // Only succeeded, live, inside the New York day.
        assertThat(m.get(Metrics.REVENUE)).isEqualTo(150.0);
        // Active calendar only; cancelled and deleted events don't count.
        assertThat(m.get(Metrics.APPOINTMENTS)).isEqualTo(2);
        assertThat(m.get(Metrics.CONVERSATIONS_UNREAD)).isEqualTo(7);
        assertThat(m.get(Metrics.SOCIAL_ACCOUNTS)).isEqualTo(2);

        assertThat(snap.facts().businessName()).isEqualTo("Acme Roofing");
        assertThat(snap.facts().timezone()).isEqualTo("America/New_York");
        assertThat(snap.facts().city()).isEqualTo("Albany");
        assertThat(snap.facts().socialChannels()).containsExactly("facebook", "instagram");
    }

    @Test
    void todayIsTheSubAccountsOwnDay() {
        location();
        respond("/contacts/", "{\"contacts\":[],\"meta\":{\"total\":0}}");
        opportunities();
        respond("/payments/transactions", "{\"data\":[]}");
        server.expect(ExpectedCount.manyTimes(), requestTo(startsWith(BASE + "/calendars/events")))
                .andExpect(queryParam("calendarId", "cal1"))
                .andExpect(queryParam("startTime", String.valueOf(NY_DAY_START)))
                .andRespond(withSuccess("{\"events\":[]}", MediaType.APPLICATION_JSON));
        respond("/calendars/?", "{\"calendars\":[{\"id\":\"cal1\"}]}");
        respond("/conversations/search", "{\"total\":0}");
        respond("/social-media-posting/", "{\"accounts\":[]}");

        GhlLocationReader.Snapshot snap = reader.read(LOC, "tok", "Europe/London");

        assertThat(snap.failed()).isEmpty();
        server.verify();
    }

    @Test
    void aRefusedEndpointOnlyLosesItsOwnFigure() {
        location();
        respond("/contacts/", "{\"contacts\":[{}],\"meta\":{\"total\":12}}");
        opportunities();
        server.expect(ExpectedCount.manyTimes(), requestTo(startsWith(BASE + "/payments/transactions")))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));
        calendars();
        respond("/conversations/search", "{\"total\":1}");
        server.expect(ExpectedCount.manyTimes(), requestTo(startsWith(BASE + "/social-media-posting/")))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        GhlLocationReader.Snapshot snap = reader.read(LOC, "tok", null);

        assertThat(snap.failed()).containsOnlyKeys("revenue", "social accounts");
        assertThat(snap.failed().get("revenue")).contains("scopes");
        assertThat(byMetric(snap)).containsKeys(Metrics.CONTACTS_TOTAL, Metrics.APPOINTMENTS)
                .doesNotContainKeys(Metrics.REVENUE, Metrics.SOCIAL_ACCOUNTS);
        // Social accounts couldn't be read, so the facts don't claim the customer has none.
        assertThat(snap.facts().socialChannels()).isNull();
    }

    @Test
    void aRejectedTokenStopsTheRead() {
        server.expect(requestTo(startsWith(BASE + "/locations/" + LOC))).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        GhlLocationReader.Snapshot snap = reader.read(LOC, "tok", null);

        assertThat(snap.tokenRejected()).isTrue();
        assertThat(snap.kpis()).isEmpty();
        server.verify();
    }
}
