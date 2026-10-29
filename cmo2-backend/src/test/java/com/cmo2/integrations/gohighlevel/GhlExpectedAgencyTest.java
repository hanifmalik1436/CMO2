package com.cmo2.integrations.gohighlevel;

import com.cmo2.integrations.gohighlevel.GhlOAuthClient.TokenGrant;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** With CMO2_GHL_AGENCY_COMPANY_ID set, only that agency's install is accepted. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "cmo2.ghl.agency-company-id=the-real-agency")
class GhlExpectedAgencyTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;

    @MockBean
    GhlOAuthClient oauth;
    @MockBean
    GhlGateway gateway;

    @Test
    void anotherAgencysInstallIsRefused() throws Exception {
        String email = "expected." + UUID.randomUUID() + "@example.com";
        MvcResult res = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                        {"email":"%s","password":"superSecret123","fullName":"Owner","organizationName":"Agency"}
                        """.formatted(email)))
                .andExpect(status().isCreated()).andReturn();
        String token = json.readTree(res.getResponse().getContentAsString()).get("accessToken").asText();
        when(oauth.exchangeAgencyCode("someone-else")).thenReturn(new TokenGrant(
                "at", "rt", Instant.now().plusSeconds(86_000), null, "Company", "a-different-agency", null));

        mvc.perform(post("/api/ghl/agency/connect").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"someone-else\"}"))
                .andExpect(status().isForbidden());
    }
}
