package com.cmo2.strangeloop;

import com.cmo2.ai.AiMessages.AiRequest;
import com.cmo2.ai.AiMessages.AiResult;
import com.cmo2.ai.provider.AnthropicProvider;
import com.cmo2.ai.provider.OpenAiProvider;
import com.cmo2.analytics.service.KpiIngestor;
import com.cmo2.integrations.core.Metrics;
import com.cmo2.integrations.core.SyncModels.KpiPoint;
import com.cmo2.integrations.gohighlevel.GhlCustomValues;
import com.cmo2.integrations.gohighlevel.GhlCustomValues.CustomValue;
import com.cmo2.integrations.gohighlevel.capability.Actor;
import com.cmo2.shared.security.TenantContext;
import com.cmo2.strangeloop.experiments.ExperimentService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 7 end to end: real figures go in; Stage 2 records what Claude reads in them; Stage 4 names
 * the metric going the wrong way; Stage 5 proposes experiments from the fixed menu only; the owner
 * decides; drafts follow Approvals; and a later cycle measures the applied experiment against its
 * baseline. AI providers and the GHL custom-values call are mocked; everything else is real.
 */
@SpringBootTest(properties = {"cmo2.ai.provider=anthropic", "cmo2.loop.drafts-per-cycle=0",
        "cmo2.loop.experiment-measure-after-days=0"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LearningAndExperimentsTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    KpiIngestor ingestor;
    @Autowired
    ExperimentService experimentService;

    @MockBean
    AnthropicProvider claude;
    @MockBean
    OpenAiProvider openAi;
    @MockBean
    GhlCustomValues customValues;

    private final List<AiRequest> claudeRequests = new ArrayList<>();
    private final AtomicReference<String> optimizationAnswer = new AtomicReference<>();

    @BeforeEach
    void providers() {
        when(claude.name()).thenReturn("anthropic");
        when(claude.isConfigured()).thenReturn(true);
        when(openAi.name()).thenReturn("openai");
        when(openAi.isConfigured()).thenReturn(true);
        when(claude.generate(any(), anyString())).thenAnswer(inv -> {
            AiRequest r = inv.getArgument(0);
            claudeRequests.add(r);
            String text = switch (r.kind()) {
                case "LEARNING" -> """
                        {"observations":["New leads fell this week while contacts kept growing."],
                         "hypotheses":[{"statement":"A clearer offer post would lift new leads","metric":"leads",
                                        "expectedDirection":"up","confidence":0.6}],
                         "confidence":0.9}""";
                case "OPTIMIZATION" -> optimizationAnswer.get();
                default -> "{\"objective\":\"Grow\",\"rationale\":\"x\",\"posts\":[]}";
            };
            return new AiResult(text, 500, 100, "claude-opus-5-5");
        });
        when(openAi.generate(any(), anyString())).thenReturn(new AiResult("Spring roof check post", 200, 80, "gpt-5"));
        when(customValues.list(anyLong())).thenReturn(List.of(
                new CustomValue("cv1", "cmo2_email_1_subject", "Your free roof survey"),
                new CustomValue("cv2", "cmo2_offer_line", "Free survey this month")));
        optimizationAnswer.set("""
                {"proposals":[
                  {"hypothesis":"An offer-led post brings more leads","action":"DRAFT_SOCIAL_POST","metric":"leads",
                   "expectedDirection":"up","postBrief":"Free roof survey for spring storm damage; book now.",
                   "customValueId":"","newValue":"","suggestion":""},
                  {"hypothesis":"A sharper subject line gets more replies","action":"UPDATE_CUSTOM_VALUE","metric":"leads",
                   "expectedDirection":"up","postBrief":"","customValueId":"cv1","newValue":"Storm damage? Free roof check this week",
                   "suggestion":""}
                ]}""");
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    private String register() throws Exception {
        MvcResult res = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                        {"email":"learn.%s@example.com","password":"superSecret123","fullName":"Owner","organizationName":"Agency"}
                        """.formatted(UUID.randomUUID())))
                .andExpect(status().isCreated()).andReturn();
        String token = read(res).get("accessToken").asText();
        mvc.perform(post("/api/billing/simulate-activation").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"plan\":\"PRO\"}")).andExpect(status().isOk());
        return token;
    }

    private long tenantId(String token) throws Exception {
        return read(mvc.perform(get("/api/tenant/current").header("Authorization", "Bearer " + token)).andReturn()).get("id").asLong();
    }

    private long customer(String token) throws Exception {
        return read(mvc.perform(post("/api/companies").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Acme Roofing\"}"))
                .andExpect(status().isCreated()).andReturn()).get("id").asLong();
    }

    private void sync(long tenant, long company, Instant at, double contacts) {
        TenantContext.set(tenant, null);
        try {
            ingestor.ingest(company, "GOHIGHLEVEL", List.of(new KpiPoint(Metrics.CONTACTS_TOTAL, contacts),
                    new KpiPoint(Metrics.PIPELINE_VALUE, 5000)), at, ZoneOffset.UTC);
        } finally {
            TenantContext.clear();
        }
    }

    private JsonNode read(MvcResult res) throws Exception {
        return json.readTree(res.getResponse().getContentAsString());
    }

    /** Runs a cycle and returns its five stage outputs, by stage number (index 0 = stage 1). */
    private List<JsonNode> runCycle(String token, long company, int n) throws Exception {
        mvc.perform(post("/api/companies/" + company + "/loop/cycles").header("Authorization", "Bearer " + token))
                .andExpect(status().isAccepted());
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(150)).untilAsserted(() -> {
            JsonNode cycles = read(mvc.perform(get("/api/companies/" + company + "/loop/cycles")
                    .header("Authorization", "Bearer " + token)).andReturn());
            assertThat(cycles).hasSize(n);
            assertThat(cycles.get(0).get("status").asText()).isEqualTo("COMPLETED");
        });
        long id = read(mvc.perform(get("/api/companies/" + company + "/loop/cycles")
                .header("Authorization", "Bearer " + token)).andReturn()).get(0).get("id").asLong();
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode stage : read(mvc.perform(get("/api/loop/cycles/" + id).header("Authorization", "Bearer " + token)).andReturn()).get("stages")) {
            out.add(json.readTree(stage.get("dataJson").asText()));
        }
        return out;
    }

    @Test
    void learnsProposesWaitsForTheOwnerAndMeasuresTheResult() throws Exception {
        String token = register();
        long tenant = tenantId(token);
        long company = customer(token);
        Instant now = Instant.now();
        sync(tenant, company, now.minus(Duration.ofDays(9)), 100);  // first day seen
        sync(tenant, company, now.minus(Duration.ofDays(8)), 130);  // 30 new leads in the earlier week
        sync(tenant, company, now.minus(Duration.ofDays(1)), 140);  // only 10 new leads this week

        // ---- Cycle 1 -------------------------------------------------------------------------
        List<JsonNode> stages = runCycle(token, company, 1);

        JsonNode insights = stages.get(1);
        assertThat(insights.get("source").asText()).isEqualTo("learning:ai");
        assertThat(insights.get("hypotheses").get(0).get("metric").asText()).isEqualTo("leads");
        AiRequest learningAsk = claudeRequests.stream().filter(r -> "LEARNING".equals(r.kind())).findFirst().orElseThrow();
        assertThat(learningAsk.userPrompt()).contains("New leads (7-day total): 10 vs 30 the week before (-66.7%)");
        assertThat(learningAsk.jsonSchema().toString()).contains("leads"); // hypotheses may only name metrics in the figures

        JsonNode learning = read(mvc.perform(get("/api/companies/" + company + "/learning")
                .header("Authorization", "Bearer " + token)).andReturn());
        assertThat(learning.get(0).get("source").asText()).isEqualTo("ai");
        assertThat(learning.get(0).get("observations").get(0).asText()).contains("New leads fell");

        JsonNode gaps = stages.get(3);
        assertThat(gaps.get("gaps").toString()).contains("\"metric\":\"leads\"");

        JsonNode optimization = stages.get(4);
        assertThat(optimization.get("proposed")).hasSize(2);
        AiRequest optimizationAsk = claudeRequests.stream().filter(r -> "OPTIMIZATION".equals(r.kind())).findFirst().orElseThrow();
        assertThat(optimizationAsk.jsonSchema().toString()).contains("DRAFT_SOCIAL_POST", "UPDATE_CUSTOM_VALUE", "SUGGESTION", "cv1");
        assertThat(optimizationAsk.userPrompt()).contains("cmo2_email_1_subject").contains("New leads down");
        // Proposing didn't touch GHL: the custom value change waits for the owner.
        verify(customValues, never()).update(anyLong(), anyString(), anyString(), any(), any());

        JsonNode list = read(mvc.perform(get("/api/companies/" + company + "/experiments")
                .header("Authorization", "Bearer " + token)).andReturn());
        JsonNode draftExp = find(list, "DRAFT_SOCIAL_POST");
        JsonNode valueExp = find(list, "UPDATE_CUSTOM_VALUE");
        assertThat(draftExp.get("status").asText()).isEqualTo("PROPOSED");
        assertThat(draftExp.get("assetId").asLong()).isPositive();
        assertThat(draftExp.get("baselineValue").asDouble()).isEqualTo(10);
        assertThat(valueExp.get("riskTier").asText()).isEqualTo("REVERSIBLE_WRITE");

        // A draft experiment is decided in Approvals, not here.
        mvc.perform(post("/api/experiments/" + draftExp.get("id").asLong() + "/approve").header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict());

        // ---- Owner decisions ------------------------------------------------------------------
        long valueId = valueExp.get("id").asLong();
        long draftId = draftExp.get("assetId").asLong();
        mvc.perform(post("/api/experiments/" + valueId + "/approve").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"Try it for a week\"}"))
                .andExpect(status().isOk());
        verify(customValues).update(eq(company), eq("cv1"), eq("Storm damage? Free roof check this week"),
                eq(Actor.HUMAN), eq("experiment:" + valueId));
        mvc.perform(post("/api/assets/" + draftId + "/reject").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Off brand\"}")).andExpect(status().isOk());

        list = read(mvc.perform(get("/api/companies/" + company + "/experiments").header("Authorization", "Bearer " + token)).andReturn());
        assertThat(find(list, "UPDATE_CUSTOM_VALUE").get("status").asText()).isEqualTo("APPLIED");
        assertThat(find(list, "DRAFT_SOCIAL_POST").get("status").asText()).isEqualTo("REJECTED");
        assertThat(find(list, "DRAFT_SOCIAL_POST").get("decisionNote").asText()).contains("Off brand");

        // ---- Cycle 2: the applied experiment is measured; new leads went up as expected --------
        sync(tenant, company, Instant.now(), 175); // 35 new today
        optimizationAnswer.set("""
                {"proposals":[{"hypothesis":"Ask happy customers for reviews","action":"SUGGESTION","metric":"leads",
                  "expectedDirection":"up","postBrief":"","customValueId":"","newValue":"","suggestion":""}]}""");
        stages = runCycle(token, company, 2);

        JsonNode measured = stages.get(1).get("experimentsMeasured");
        assertThat(measured).hasSize(1);
        assertThat(measured.get(0).asText()).contains("as expected").contains("signal, not proof");
        assertThat(stages.get(4).get("skipped").get(0).asText()).isEqualTo("an empty suggestion");
        list = read(mvc.perform(get("/api/companies/" + company + "/experiments").header("Authorization", "Bearer " + token)).andReturn());
        assertThat(find(list, "UPDATE_CUSTOM_VALUE").get("outcome").asText()).isEqualTo("SUPPORTED");

        // The next plan and the next learning both see the result.
        AiRequest secondPlan = claudeRequests.stream().filter(r -> "CYCLE_PLAN".equals(r.kind())).reduce((a, b) -> b).orElseThrow();
        AiRequest secondLearning = claudeRequests.stream().filter(r -> "LEARNING".equals(r.kind())).reduce((a, b) -> b).orElseThrow();
        assertThat(secondLearning.userPrompt()).contains("Experiment #" + valueId);
        assertThat(secondPlan).isNotNull();

        // Cycle 2's record shows the decisions made between cycles.
        JsonNode record = read(mvc.perform(get("/api/companies/" + company + "/loop/memory")
                .header("Authorization", "Bearer " + token)).andReturn()).get(0);
        assertThat(record.get("decisions").toString()).contains("You approved experiment #" + valueId);
    }

    @Test
    void aProposalWaitingForTheOwnerBecomesATaskInTheNextRecord() throws Exception {
        optimizationAnswer.set("""
                {"proposals":[{"hypothesis":"Reviews build trust","action":"SUGGESTION","metric":"leads",
                  "expectedDirection":"up","postBrief":"","customValueId":"","newValue":"","suggestion":"Ask last month's customers for a Google review"}]}""");
        String token = register();
        long tenant = tenantId(token);
        long company = customer(token);
        Instant now = Instant.now();
        sync(tenant, company, now.minus(Duration.ofDays(9)), 100);
        sync(tenant, company, now.minus(Duration.ofDays(1)), 120);

        runCycle(token, company, 1);
        runCycle(token, company, 2);

        JsonNode record = read(mvc.perform(get("/api/companies/" + company + "/loop/memory")
                .header("Authorization", "Bearer " + token)).andReturn()).get(0);
        assertThat(record.get("tasksPending").toString())
                .contains("Suggestion: Ask last month's customers for a Google review")
                .contains(":decide");
    }

    @Test
    void onlyTheOwnerOrAnAdminDecidesAndOtherAgenciesSeeNothing() throws Exception {
        String ownerA = register();
        long company = customer(ownerA);
        String ownerB = register();

        mvc.perform(get("/api/companies/" + company + "/learning").header("Authorization", "Bearer " + ownerB))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/companies/" + company + "/experiments").header("Authorization", "Bearer " + ownerB))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/experiments/999999/approve").header("Authorization", "Bearer " + ownerB))
                .andExpect(status().isNotFound());

        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "strategist", null, List.of(new SimpleGrantedAuthority("ROLE_STRATEGIST"))));
        assertThatThrownBy(() -> experimentService.approve(1L, null)).isInstanceOf(AccessDeniedException.class);
    }

    private static JsonNode find(JsonNode list, String action) {
        for (JsonNode e : list) {
            if (action.equals(e.get("action").asText())) return e;
        }
        throw new AssertionError("no " + action + " experiment in " + list);
    }
}
