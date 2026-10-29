package com.cmo2.strangeloop;

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
 * Phase 2 exit criterion: "Run Cycle" executes all 5 stages, the iteration is stored
 * with insights/gaps JSON, and the stage runs are persisted (enabling crash-resume).
 * The cycle runs asynchronously, so we poll until it completes.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StrangeLoopCycleTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;

    private String register(String email) throws Exception {
        String body = """
                {"email":"%s","password":"superSecret123","fullName":"Loop User","organizationName":"Loop Org"}
                """.formatted(email);
        MvcResult res = mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(res.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private long createCompany(String token) throws Exception {
        MvcResult res = mvc.perform(post("/api/companies")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Loop Brand\"}"))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(res.getResponse().getContentAsString()).get("id").asLong();
    }

    @Test
    void runCycleExecutesAllFiveStagesAndStoresArtifacts() throws Exception {
        String token = register("loop.owner@example.com");
        long companyId = createCompany(token);

        // Kick off the cycle (async → 202 with a RUNNING iteration).
        MvcResult started = mvc.perform(post("/api/companies/" + companyId + "/loop/cycles")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andReturn();
        long iterationId = json.readTree(started.getResponse().getContentAsString()).get("id").asLong();

        // Poll until the background cycle finishes.
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(150)).untilAsserted(() -> {
            MvcResult r = mvc.perform(get("/api/loop/cycles/" + iterationId)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk()).andReturn();
            JsonNode detail = json.readTree(r.getResponse().getContentAsString());
            assertThat(detail.get("iteration").get("status").asText()).isEqualTo("COMPLETED");
        });

        // Verify the persisted artifacts.
        MvcResult r = mvc.perform(get("/api/loop/cycles/" + iterationId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn();
        JsonNode detail = json.readTree(r.getResponse().getContentAsString());

        assertThat(detail.get("stages")).hasSize(5);
        detail.get("stages").forEach(s -> assertThat(s.get("status").asText()).isEqualTo("COMPLETED"));
        assertThat(detail.get("insightsJson").asText()).contains("learning");
        assertThat(detail.get("gapsJson").asText()).contains("reporting");
    }
}
