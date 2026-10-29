package com.cmo2.integrations.gohighlevel;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GhlGatewayTest {

    private static final String BASE = "https://ghl.test";

    private MockRestServiceServer server;
    private GhlGateway gateway;
    private final List<Duration> sleeps = new ArrayList<>();

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        gateway = new GhlGateway(builder, sleeps::add);
    }

    @Test
    void picksTheVersionHeaderByPath() {
        assertThat(GhlGateway.versionFor("/conversations/search")).isEqualTo("2021-04-15");
        assertThat(GhlGateway.versionFor("/calendars/events/appointments")).isEqualTo("2021-04-15");
        assertThat(GhlGateway.versionFor("/locations/search")).isEqualTo("v3");
        assertThat(GhlGateway.versionFor("/saas/locations")).isEqualTo("v3");
        assertThat(GhlGateway.versionFor("/locations/loc1")).isEqualTo("2021-07-28");
        assertThat(GhlGateway.versionFor("/contacts/")).isEqualTo("2021-07-28");
    }

    @Test
    void countUsesMetaTotalFromASingleOneRowRequest() {
        server.expect(requestTo(startsWith(BASE + "/contacts/")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("locationId", "loc1"))
                .andExpect(queryParam("limit", "1"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer tok"))
                .andExpect(header("Version", "2021-07-28"))
                .andRespond(withSuccess("{\"contacts\":[{}],\"meta\":{\"total\":4213}}", MediaType.APPLICATION_JSON));

        long total = gateway.count("/contacts/", Map.of("locationId", "loc1"), "contacts", "tok", "loc1");

        assertThat(total).isEqualTo(4213);
        server.verify();
    }

    @Test
    void countPagesThroughTheCursorWhenGhlGivesNoTotal() {
        server.expect(requestTo(startsWith(BASE + "/contacts/"))).andExpect(queryParam("limit", "1"))
                .andRespond(withSuccess("{\"contacts\":[{}]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(startsWith(BASE + "/contacts/"))).andExpect(queryParam("limit", "100"))
                .andRespond(withSuccess("{\"contacts\":[{\"id\":\"a\"},{\"id\":\"b\"}],"
                        + "\"meta\":{\"startAfterId\":\"b\",\"startAfter\":1700000000000}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(startsWith(BASE + "/contacts/")))
                .andExpect(queryParam("startAfterId", "b"))
                .andExpect(queryParam("startAfter", "1700000000000"))
                .andRespond(withSuccess("{\"contacts\":[{\"id\":\"c\"}],\"meta\":{}}", MediaType.APPLICATION_JSON));

        long total = gateway.count("/contacts/", Map.of("locationId", "loc1"), "contacts", "tok", "loc1");

        assertThat(total).isEqualTo(3);
        server.verify();
    }

    @Test
    void retriesAGetOn429HonouringRetryAfterButCappedAt30Seconds() {
        HttpHeaders retryAfter = new HttpHeaders();
        retryAfter.add(HttpHeaders.RETRY_AFTER, "120");
        server.expect(requestTo(startsWith(BASE + "/locations/loc1")))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).headers(retryAfter));
        server.expect(requestTo(startsWith(BASE + "/locations/loc1")))
                .andRespond(withSuccess("{\"location\":{\"id\":\"loc1\"}}", MediaType.APPLICATION_JSON));

        gateway.get("/locations/loc1", Map.of(), "tok", "loc1");

        assertThat(sleeps).containsExactly(Duration.ofSeconds(30));
        server.verify();
    }

    @Test
    void retriesWithExponentialBackoffWhenThereIsNoRetryAfter() {
        server.expect(requestTo(startsWith(BASE + "/locations/loc1"))).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(requestTo(startsWith(BASE + "/locations/loc1"))).andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        server.expect(requestTo(startsWith(BASE + "/locations/loc1")))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        gateway.get("/locations/loc1", Map.of(), "tok", "loc1");

        assertThat(sleeps).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2));
    }

    @Test
    void givesUpAfterFourAttempts() {
        for (int i = 0; i < 4; i++) {
            server.expect(requestTo(startsWith(BASE + "/locations/loc1"))).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        }

        assertThatThrownBy(() -> gateway.get("/locations/loc1", Map.of(), "tok", "loc1"))
                .isInstanceOf(GhlApiException.class)
                .satisfies(e -> assertThat(((GhlApiException) e).status()).isEqualTo(500));
        assertThat(sleeps).hasSize(3);
        server.verify();
    }

    @Test
    void neverRetriesAWrite() {
        server.expect(requestTo(startsWith(BASE + "/contacts/c1/tags")))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> gateway.post("/contacts/c1/tags", Map.of("tags", List.of("new-lead")), "tok", "loc1"))
                .isInstanceOf(GhlApiException.class);
        assertThat(sleeps).isEmpty();
        server.verify();
    }

    @Test
    void doesNotRetryAClientError() {
        server.expect(requestTo(startsWith(BASE + "/locations/loc1"))).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> gateway.get("/locations/loc1", Map.of(), "tok", "loc1"))
                .isInstanceOf(GhlApiException.class)
                .satisfies(e -> assertThat(((GhlApiException) e).isUnauthorized()).isTrue());
        assertThat(sleeps).isEmpty();
    }

    @Test
    void removingTagsSendsTheTagsInTheBody() {
        server.expect(requestTo(startsWith(BASE + "/contacts/c1/tags")))
                .andExpect(method(HttpMethod.DELETE))
                .andExpect(content().json("{\"tags\":[\"new-lead\"]}"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        gateway.delete("/contacts/c1/tags", Map.of("tags", List.of("new-lead")), "tok", "loc1");

        server.verify();
    }

    @Test
    void remembersGhlsRateLimitHeaders() {
        HttpHeaders limits = new HttpHeaders();
        limits.add("X-RateLimit-Remaining", "87");
        limits.add("X-RateLimit-Daily-Remaining", "199000");
        server.expect(requestTo(startsWith(BASE + "/locations/loc1")))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON).headers(limits));

        gateway.get("/locations/loc1", Map.of(), "tok", "loc1");

        assertThat(gateway.lastRateLimit("loc1")).isEqualTo(new GhlGateway.RateLimitSnapshot(87, 199000));
    }

    @Test
    void refusesACallWithNoExplicitLocation() {
        assertThatThrownBy(() -> gateway.get("/contacts/", Map.of(), "tok", " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesACallWithNoToken() {
        assertThatThrownBy(() -> gateway.get("/contacts/", Map.of(), null, "loc1"))
                .isInstanceOf(GhlApiException.class);
    }
}
