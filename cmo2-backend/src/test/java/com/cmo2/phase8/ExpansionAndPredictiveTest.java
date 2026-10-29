package com.cmo2.phase8;

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
 * Phase 8 — Expansion & advanced AI:
 * <ul>
 *   <li>GoHighLevel is the only connectable platform; retired platforms are rejected.</li>
 *   <li>Forecasting projects a KPI forward with a trend + confidence.</li>
 *   <li>The What-If simulator returns a coherent scenario projection.</li>
 * </ul>
 * Upgrades the tenant to PRO first so the multi-company / multi-connection plan limits don't
 * interfere with the expansion checks.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ExpansionAndPredictiveTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;

    private String register(String email) throws Exception {
        String body = """
                {"email":"%s","password":"superSecret123","fullName":"P8 User","organizationName":"P8 Org"}
                """.formatted(email);
        MvcResult res = mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(res.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private long createCompany(String token, String name) throws Exception {
        MvcResult res = mvc.perform(post("/api/companies").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(res.getResponse().getContentAsString()).get("id").asLong();
    }

    private void upgradePro(String token) throws Exception {
        mvc.perform(post("/api/billing/simulate-activation").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"plan\":\"PRO\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void goHighLevelIsTheOnlyConnectablePlatform() throws Exception {
        String token = register("p8.platforms@example.com");

        MvcResult res = mvc.perform(get("/api/integrations/platforms").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andReturn();

        JsonNode ghl = json.readTree(res.getResponse().getContentAsString()).get(0);
        assertThat(ghl.get("supported").asBoolean()).isTrue();
        assertThat(ghl.get("deploysAssets").asBoolean()).isTrue();
    }

    @Test
    void retiredPlatformIsRejected() throws Exception {
        String token = register("p8.connect@example.com");
        long companyId = createCompany(token, "Ads Co");

        mvc.perform(post("/api/companies/" + companyId + "/integrations")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"platform\":\"GOOGLE_ADS\",\"apiKey\":\"demo-ads-key\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void forecastProjectsAKpiForward() throws Exception {
        String token = register("p8.forecast@example.com");
        upgradePro(token);
        long companyId = createCompany(token, "Forecast Co");

        // Build history: connect GHL (demo) and sync several times so there are >= 3 points.
        MvcResult conn = mvc.perform(post("/api/companies/" + companyId + "/integrations")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"platform\":\"GOHIGHLEVEL\",\"apiKey\":\"demo-key-123456\"}"))
                .andExpect(status().isCreated()).andReturn();
        long connectionId = json.readTree(conn.getResponse().getContentAsString()).get("id").asLong();
        for (int i = 0; i < 4; i++) {
            mvc.perform(post("/api/integrations/" + connectionId + "/sync").header("Authorization", "Bearer " + token))
                    .andExpect(status().isAccepted());
        }

        mvc.perform(get("/api/companies/" + companyId + "/analytics/forecast?metric=leads&horizon=6")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metric").value("leads"))
                .andExpect(jsonPath("$.projection.length()").value(6))
                .andExpect(jsonPath("$.trend").exists())
                .andExpect(jsonPath("$.narrative").isNotEmpty());
    }

    @Test
    void whatIfSimulatesAScenario() throws Exception {
        String token = register("p8.whatif@example.com");
        long companyId = createCompany(token, "Sim Co");

        // Even with no synced data, the simulator falls back to sensible baselines.
        MvcResult res = mvc.perform(post("/api/companies/" + companyId + "/analytics/what-if")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"spendChangePct\":20,\"cplChangePct\":-10,\"conversionChangePct\":15}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metrics.length()").value(5))
                .andExpect(jsonPath("$.summary").isNotEmpty())
                .andReturn();

        JsonNode body = json.readTree(res.getResponse().getContentAsString());
        // +20% spend with -10% CPL should raise projected leads above baseline.
        JsonNode leads = null;
        for (JsonNode m : body.get("metrics")) {
            if (m.get("metric").asText().equals("leads")) leads = m;
        }
        assertThat(leads).isNotNull();
        assertThat(leads.get("projected").asDouble()).isGreaterThan(leads.get("baseline").asDouble());
        assertThat(body.get("projectedRevenue").asDouble()).isGreaterThan(0);
    }
}
