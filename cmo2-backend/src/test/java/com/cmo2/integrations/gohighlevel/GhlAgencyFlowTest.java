package com.cmo2.integrations.gohighlevel;

import com.cmo2.integrations.gohighlevel.GhlOAuthClient.TokenGrant;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 1 end to end through the real API, with only GHL's HTTP clients mocked: connect the
 * agency, list its sub-accounts, enable one as a customer, and prove the safety rules. The
 * in-memory database is shared across tests, so each test uses its own agency and sub-account
 * ids (one agency and one sub-account can belong to only one tenant).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GhlAgencyFlowTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;

    @MockBean
    GhlOAuthClient oauth;
    @MockBean
    GhlGateway gateway;

    private String agencyId;
    private String loc1;
    private String loc2;

    @BeforeEach
    void stubGhl() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        agencyId = "comp-" + suffix;
        loc1 = "loc1-" + suffix;
        loc2 = "loc2-" + suffix;

        String locations = """
                {"locations":[{"id":"%s","name":"Acme Dental %s","website":"https://acme.test","city":"Leeds"},
                              {"id":"%s","name":"Beta Gym %s"}]}
                """.formatted(loc1, suffix, loc2, suffix);
        when(gateway.get(eq("/locations/search"), anyMap(), anyString(), eq(agencyId))).thenReturn(json.readTree(locations));
        when(gateway.get(eq("/locations/" + loc1), anyMap(), eq("loc-at"), eq(loc1))).thenReturn(json.readTree(
                "{\"location\":{\"id\":\"" + loc1 + "\",\"name\":\"Acme Dental " + suffix + "\",\"website\":\"https://acme.test\"}}"));
        when(oauth.mintLocationToken(anyString(), eq(agencyId), eq(loc1))).thenReturn(
                new TokenGrant("loc-at", null, Instant.now().plusSeconds(86_000), "contacts.readonly", "Location", agencyId, loc1));
    }

    private String register() throws Exception {
        String email = "ghl." + UUID.randomUUID() + "@example.com";
        String body = """
                {"email":"%s","password":"superSecret123","fullName":"Owner","organizationName":"Agency %s"}
                """.formatted(email, email);
        MvcResult res = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(res.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private TokenGrant agencyGrant(Instant expiresAt) {
        return new TokenGrant("agency-at", "agency-rt", expiresAt, "locations.readonly oauth.write", "Company", agencyId, null);
    }

    private void connectAgency(String token, String code) throws Exception {
        mvc.perform(post("/api/ghl/agency/connect").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connected").value(true))
                .andExpect(jsonPath("$.ghlCompanyId").value(agencyId));
    }

    @Test
    void connectListAndEnableACustomer() throws Exception {
        String token = register();
        when(oauth.exchangeAgencyCode("good-code")).thenReturn(agencyGrant(Instant.now().plusSeconds(86_000)));

        mvc.perform(get("/api/ghl/agency").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connected").value(false));

        connectAgency(token, "good-code");

        mvc.perform(get("/api/ghl/sub-accounts").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].locationId").value(loc1))
                .andExpect(jsonPath("$[0].enabled").value(false));

        mvc.perform(post("/api/ghl/sub-accounts/" + loc1 + "/enable").header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.companyId").isNumber())
                .andExpect(jsonPath("$.health").value("GREEN"));

        mvc.perform(get("/api/companies").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].ghlLocationId").value(loc1));

        mvc.perform(get("/api/ghl/sub-accounts").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$[0].enabled").value(true))
                .andExpect(jsonPath("$[1].enabled").value(false));

        // The same sub-account can't be enabled twice.
        mvc.perform(post("/api/ghl/sub-accounts/" + loc1 + "/enable").header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict());
    }

    @Test
    void aSubAccountOutsideTheAgencyCannotBeEnabled() throws Exception {
        String token = register();
        when(oauth.exchangeAgencyCode("code-x")).thenReturn(agencyGrant(Instant.now().plusSeconds(86_000)));
        connectAgency(token, "code-x");
        when(oauth.mintLocationToken(anyString(), eq(agencyId), eq("foreign")))
                .thenThrow(new GhlApiException(400, "location not in company"));

        mvc.perform(post("/api/ghl/sub-accounts/foreign/enable").header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/companies").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void aMalformedSubAccountIdIsRejected() throws Exception {
        String token = register();
        when(oauth.exchangeAgencyCode("code-m")).thenReturn(agencyGrant(Instant.now().plusSeconds(86_000)));
        connectAgency(token, "code-m");

        mvc.perform(post("/api/ghl/sub-accounts/bad.id!/enable").header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aSingleLocationInstallIsRefused() throws Exception {
        String token = register();
        when(oauth.exchangeAgencyCode("location-install"))
                .thenReturn(new TokenGrant("at", "rt", Instant.now().plusSeconds(86_000), null, "Location", agencyId, "loc9"));

        mvc.perform(post("/api/ghl/agency/connect").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"location-install\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anExpiringAgencyTokenIsRefreshedBeforeUse() throws Exception {
        String token = register();
        when(oauth.exchangeAgencyCode("short-lived")).thenReturn(agencyGrant(Instant.now().plusSeconds(60)));
        when(oauth.refreshAgency("agency-rt")).thenReturn(
                new TokenGrant("fresh-at", "fresh-rt", Instant.now().plusSeconds(86_000), null, "Company", agencyId, null));
        connectAgency(token, "short-lived");

        mvc.perform(get("/api/ghl/sub-accounts").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        verify(oauth).refreshAgency("agency-rt");
        verify(gateway).get(eq("/locations/search"), anyMap(), eq("fresh-at"), eq(agencyId));
        verify(gateway, never()).get(eq("/locations/search"), anyMap(), eq("agency-at"), eq(agencyId));
    }

    @Test
    void aFailedRefreshMarksTheAgencyForReconnection() throws Exception {
        String token = register();
        when(oauth.exchangeAgencyCode("revoked")).thenReturn(agencyGrant(Instant.now().plusSeconds(60)));
        when(oauth.refreshAgency("agency-rt")).thenThrow(new GhlApiException(401, "invalid_grant"));
        connectAgency(token, "revoked");

        mvc.perform(get("/api/ghl/sub-accounts").header("Authorization", "Bearer " + token))
                .andExpect(status().isBadGateway());
        mvc.perform(get("/api/ghl/agency").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.status").value("ERROR"))
                .andExpect(jsonPath("$.lastError").isNotEmpty());
    }

    @Test
    void tenantsNeverSeeEachOthersAgency() throws Exception {
        String tokenA = register();
        String tokenB = register();
        when(oauth.exchangeAgencyCode("code-a")).thenReturn(agencyGrant(Instant.now().plusSeconds(86_000)));
        connectAgency(tokenA, "code-a");

        mvc.perform(get("/api/ghl/agency").header("Authorization", "Bearer " + tokenB))
                .andExpect(jsonPath("$.connected").value(false));
        mvc.perform(get("/api/ghl/sub-accounts").header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isBadRequest());

        // One GHL agency can belong to only one CMO² tenant.
        when(oauth.exchangeAgencyCode("code-b")).thenReturn(agencyGrant(Instant.now().plusSeconds(86_000)));
        mvc.perform(post("/api/ghl/agency/connect").header("Authorization", "Bearer " + tokenB)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"code-b\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void agencyEndpointsNeedAuthentication() throws Exception {
        mvc.perform(get("/api/ghl/agency")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/ghl/sub-accounts")).andExpect(status().isUnauthorized());
    }
}
