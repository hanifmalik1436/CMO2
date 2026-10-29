package com.cmo2.phase6;

import com.cmo2.integrations.core.Metrics;

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
 * Phase 6 exit criteria, demonstrated end-to-end:
 * <ol>
 *   <li>A simulated CPL spike fires an alert through email + Slack channels (the
 *       scheduled sweep would do the same within the 5-minute window; simulate proves the
 *       same code path instantly and deterministically).</li>
 *   <li>A leadership PDF report generates, lands in the Reports module, and downloads as a
 *       real PDF — the same path the weekly scheduler uses.</li>
 *   <li>Analytics time-series + portfolio roll-up read back tenant-scoped KPIs.</li>
 *   <li>Tenant isolation holds across the new endpoints.</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AnalyticsAlertsReportsTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;

    private String register(String email) throws Exception {
        String body = """
                {"email":"%s","password":"superSecret123","fullName":"P6 User","organizationName":"P6 Org"}
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

    private void connectAndSync(String token, long companyId) throws Exception {
        MvcResult res = mvc.perform(post("/api/companies/" + companyId + "/integrations")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"platform\":\"GOHIGHLEVEL\",\"apiKey\":\"demo-key-123456\"}"))
                .andExpect(status().isCreated()).andReturn();
        long connectionId = json.readTree(res.getResponse().getContentAsString()).get("id").asLong();
        mvc.perform(post("/api/integrations/" + connectionId + "/sync")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isAccepted());
    }

    @Test
    void cplSpikeFiresEmailAndSlackAlert() throws Exception {
        String token = register("p6.alerts@example.com");
        long companyId = createCompany(token, "Spike Co");
        connectAndSync(token, companyId);

        // Rule: fire CRITICAL when CPL climbs above $10, to email + Slack.
        String rule = """
                {"metric":"cpl","operator":"GT","threshold":10,"severity":"CRITICAL",
                 "channels":[{"type":"EMAIL","target":"ceo@example.com"},{"type":"SLACK","target":null}]}
                """;
        mvc.perform(post("/api/companies/" + companyId + "/alert-rules")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(rule))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.metric").value("cpl"))
                .andExpect(jsonPath("$.active").value(true));

        // Simulate a spike — same path the scheduler runs, just immediate.
        MvcResult sim = mvc.perform(post("/api/companies/" + companyId + "/alerts/simulate")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"metric\":\"cpl\",\"value\":999.0}"))
                .andExpect(status().isOk()).andReturn();

        JsonNode alerts = json.readTree(sim.getResponse().getContentAsString());
        JsonNode fired = null;
        for (JsonNode a : alerts) {
            if (a.get("metric").asText().equals("cpl") && a.get("status").asText().equals("FIRED")) {
                fired = a;
            }
        }
        assertThat(fired).as("a CPL alert should have fired").isNotNull();
        assertThat(fired.get("severity").asText()).isEqualTo("CRITICAL");
        assertThat(fired.get("value").asDouble()).isEqualTo(999.0);
        String dispatched = fired.get("dispatchedJson").asText();
        assertThat(dispatched).contains("EMAIL").contains("SLACK");

        long alertId = fired.get("id").asLong();

        // The alert center lists it...
        mvc.perform(get("/api/alerts").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));

        // ...and it can be acknowledged.
        mvc.perform(post("/api/alerts/" + alertId + "/acknowledge")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACKED"));
    }

    @Test
    void analyticsTimeseriesAndPortfolio() throws Exception {
        String token = register("p6.analytics@example.com");
        long companyId = createCompany(token, "Chart Co");
        connectAndSync(token, companyId);

        mvc.perform(get("/api/companies/" + companyId + "/analytics/timeseries?metric=leads&limit=30")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));

        mvc.perform(get("/api/analytics/portfolio").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(Metrics.ALL.size())); // one aggregate per canonical metric
    }

    @Test
    void generateAndDownloadPdfReport() throws Exception {
        String token = register("p6.reports@example.com");
        long companyId = createCompany(token, "Report Co");
        connectAndSync(token, companyId);

        MvcResult gen = mvc.perform(post("/api/companies/" + companyId + "/reports/generate")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("GENERATED"))
                .andExpect(jsonPath("$.format").value("PDF"))
                .andReturn();
        long reportId = json.readTree(gen.getResponse().getContentAsString()).get("id").asLong();

        // It appears in the Reports module listing.
        mvc.perform(get("/api/reports").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));

        // And downloads as a real PDF (magic bytes %PDF).
        MvcResult dl = mvc.perform(get("/api/reports/" + reportId + "/download")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content -> assertThat(content.getResponse().getContentType())
                        .contains(MediaType.APPLICATION_PDF_VALUE))
                .andReturn();
        byte[] pdf = dl.getResponse().getContentAsByteArray();
        assertThat(pdf.length).isGreaterThan(100);
        assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");
    }

    @Test
    void tenantsCannotDownloadEachOthersReports() throws Exception {
        String tokenA = register("p6.iso.a@example.com");
        String tokenB = register("p6.iso.b@example.com");
        long companyA = createCompany(tokenA, "Iso A");
        connectAndSync(tokenA, companyA);

        MvcResult gen = mvc.perform(post("/api/companies/" + companyA + "/reports/generate")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isCreated()).andReturn();
        long reportId = json.readTree(gen.getResponse().getContentAsString()).get("id").asLong();

        // B sees no reports and cannot download A's report by id.
        mvc.perform(get("/api/reports").header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/reports/" + reportId + "/download")
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
    }
}
