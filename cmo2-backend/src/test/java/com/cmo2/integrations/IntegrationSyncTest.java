package com.cmo2.integrations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 3 exit criterion: connect GoHighLevel → sync → KPIs land and surface on the
 * dashboard endpoint. Also re-asserts tenant isolation across the new endpoints.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IntegrationSyncTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;

    private String register(String email) throws Exception {
        String body = """
                {"email":"%s","password":"superSecret123","fullName":"Int User","organizationName":"Int Org"}
                """.formatted(email);
        MvcResult res = mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(res.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private long createCompany(String token, String name) throws Exception {
        MvcResult res = mvc.perform(post("/api/companies")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(res.getResponse().getContentAsString()).get("id").asLong();
    }

    private long connectGhl(String token, long companyId) throws Exception {
        MvcResult res = mvc.perform(post("/api/companies/" + companyId + "/integrations")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"platform\":\"GOHIGHLEVEL\",\"apiKey\":\"demo-key-123456\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.maskedKey").value("••••3456"))   // key never returned in clear
                .andExpect(jsonPath("$.health").value("GREEN"))
                .andReturn();
        return json.readTree(res.getResponse().getContentAsString()).get("id").asLong();
    }

    @Test
    void connectSyncAndReadKpis() throws Exception {
        String token = register("int.owner@example.com");
        long companyId = createCompany(token, "Brand X");
        long connectionId = connectGhl(token, companyId);

        // Run a sync now — demo connector ingests the 6 KPIs synchronously.
        mvc.perform(post("/api/integrations/" + connectionId + "/sync")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.kpisIngested").value(6));

        // Dashboard endpoint returns the latest value per metric.
        MvcResult kpis = mvc.perform(get("/api/companies/" + companyId + "/kpis/latest")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn();
        JsonNode arr = json.readTree(kpis.getResponse().getContentAsString());
        assertThat(arr).hasSize(6);

        // Sync logs recorded.
        mvc.perform(get("/api/integrations/" + connectionId + "/sync-jobs")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("SUCCESS"));
    }

    @Test
    void tenantsCannotReadEachOthersConnections() throws Exception {
        String tokenA = register("int.a@example.com");
        String tokenB = register("int.b@example.com");
        long companyA = createCompany(tokenA, "A Brand");
        long companyB = createCompany(tokenB, "B Brand");
        connectGhl(tokenA, companyA);
        long connB = connectGhl(tokenB, companyB);

        // A cannot see B's connection list...
        mvc.perform(get("/api/companies/" + companyB + "/integrations")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(jsonPath("$.length()").value(0));

        // ...nor sync B's connection by id.
        mvc.perform(post("/api/integrations/" + connB + "/sync")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNotFound());
    }
}
