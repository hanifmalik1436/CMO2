package com.cmo2.integrations.gohighlevel;

import com.cmo2.integrations.gohighlevel.GhlOAuthClient.TokenGrant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GhlOAuthClientTest {

    private static final String BASE = "https://ghl.test";

    private MockRestServiceServer server;
    private GhlOAuthClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new GhlOAuthClient(builder, "client-1", "secret-1", "http://localhost:5173/settings/ghl/callback");
    }

    @Test
    void exchangesTheInstallCodeForAgencyTokens() {
        server.expect(requestTo(BASE + "/oauth/token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().formDataContains(Map.of(
                        "client_id", "client-1",
                        "client_secret", "secret-1",
                        "grant_type", "authorization_code",
                        "code", "abc",
                        "user_type", "Company")))
                .andRespond(withSuccess("""
                        {"access_token":"at","token_type":"Bearer","expires_in":86399,"refresh_token":"rt",
                         "scope":"locations.readonly oauth.write","userType":"Company","companyId":"comp1"}
                        """, MediaType.APPLICATION_JSON));

        TokenGrant grant = client.exchangeAgencyCode("abc");

        assertThat(grant.accessToken()).isEqualTo("at");
        assertThat(grant.refreshToken()).isEqualTo("rt");
        assertThat(grant.userType()).isEqualTo("Company");
        assertThat(grant.companyId()).isEqualTo("comp1");
        assertThat(grant.expiresAt()).isAfter(Instant.now().plusSeconds(86_000));
        server.verify();
    }

    @Test
    void refreshesWithTheRefreshTokenGrant() {
        server.expect(requestTo(BASE + "/oauth/token"))
                .andExpect(content().formDataContains(Map.of("grant_type", "refresh_token", "refresh_token", "old-rt")))
                .andRespond(withSuccess("{\"access_token\":\"at2\",\"refresh_token\":\"rt2\",\"expires_in\":86399}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.refreshAgency("old-rt").refreshToken()).isEqualTo("rt2");
    }

    @Test
    void mintsALocationTokenWithTheAgencyToken() {
        server.expect(requestTo(BASE + "/oauth/locationToken"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer agency-at"))
                .andExpect(header("Version", "2021-07-28"))
                .andExpect(content().formDataContains(Map.of("companyId", "comp1", "locationId", "loc1")))
                .andRespond(withSuccess("{\"access_token\":\"loc-at\",\"expires_in\":86399,\"locationId\":\"loc1\"}",
                        MediaType.APPLICATION_JSON));

        TokenGrant grant = client.mintLocationToken("agency-at", "comp1", "loc1");

        assertThat(grant.accessToken()).isEqualTo("loc-at");
        assertThat(grant.locationId()).isEqualTo("loc1");
    }

    @Test
    void surfacesGhlErrorsWithTheirStatus() {
        server.expect(requestTo(BASE + "/oauth/locationToken"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).body("{\"message\":\"location not in company\"}"));

        assertThatThrownBy(() -> client.mintLocationToken("agency-at", "comp1", "other-loc"))
                .isInstanceOf(GhlApiException.class)
                .hasMessageContaining("location not in company")
                .satisfies(e -> assertThat(((GhlApiException) e).status()).isEqualTo(400));
    }

    @Test
    void refusesToExchangeWithoutAConfiguredApp() {
        GhlOAuthClient unconfigured = new GhlOAuthClient(RestClient.builder().baseUrl(BASE), "", "", "");

        assertThat(unconfigured.isConfigured()).isFalse();
        assertThatThrownBy(() -> unconfigured.exchangeAgencyCode("abc"))
                .isInstanceOf(GhlApiException.class)
                .hasMessageContaining("not configured");
    }
}
