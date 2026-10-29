package com.cmo2.strategists;

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

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 5 exit criterion: run the Market Analysis agent → receive its deliverables
 * (TAM/SAM/SOM…) → approve RG-1 → the deliverables land in the workspace → the next
 * Strange Loop cycle's Stage 1 consumes them.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StrategistCouncilTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;

    private String register(String email) throws Exception {
        String body = """
                {"email":"%s","password":"superSecret123","fullName":"Strat User","organizationName":"Strat Org"}
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

    @Test
    void marketAnalysisToGateToStrangeLoop() throws Exception {
        String token = register("strat.owner@example.com");
        long companyId = createCompany(token, "Acme");

        // Council is seeded.
        mvc.perform(get("/api/strategists/agents").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(17)));

        // Start a Market Analysis session and produce deliverables.
        MvcResult start = mvc.perform(post("/api/companies/" + companyId + "/strategists/sessions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentCode\":\"MARKET_ANALYSIS\"}"))
                .andExpect(status().isCreated()).andReturn();
        long sessionId = json.readTree(start.getResponse().getContentAsString()).get("id").asLong();

        mvc.perform(post("/api/strategists/sessions/" + sessionId + "/deliverables")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.deliverablesJson").value(org.hamcrest.Matchers.containsString("TAM")));

        // Gates exist; approve RG-1.
        mvc.perform(get("/api/companies/" + companyId + "/strategists/gates")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4));

        mvc.perform(post("/api/companies/" + companyId + "/strategists/gates/RG-1/approve")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        // Run a Strange Loop cycle; Stage 1 should now consume the approved deliverables.
        MvcResult cycle = mvc.perform(post("/api/companies/" + companyId + "/loop/cycles")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isAccepted()).andReturn();
        long iterationId = json.readTree(cycle.getResponse().getContentAsString()).get("id").asLong();

        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(150)).untilAsserted(() -> {
            MvcResult r = mvc.perform(get("/api/loop/cycles/" + iterationId)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk()).andReturn();
            JsonNode detail = json.readTree(r.getResponse().getContentAsString());
            assertThat(detail.get("iteration").get("status").asText()).isEqualTo("COMPLETED");
        });

        MvcResult r = mvc.perform(get("/api/loop/cycles/" + iterationId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn();
        JsonNode stages = json.readTree(r.getResponse().getContentAsString()).get("stages");
        String stage1Data = null;
        for (JsonNode s : stages) {
            if (s.get("stageNo").asInt() == 1) stage1Data = s.get("dataJson").asText();
        }
        assertThat(stage1Data).contains("strategist:approved");
    }
}
