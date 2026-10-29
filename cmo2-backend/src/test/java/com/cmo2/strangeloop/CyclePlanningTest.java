package com.cmo2.strangeloop;

import com.cmo2.ai.AiMessages.AiRequest;
import com.cmo2.ai.AiMessages.AiResult;
import com.cmo2.ai.provider.AnthropicProvider;
import com.cmo2.ai.provider.OpenAiProvider;
import com.cmo2.shared.exceptions.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Stage 1 with live routing: Claude plans the cycle (structured output) and OpenAI writes the draft
 * and its translation, all in one cycle and all recorded against the customer. The two providers'
 * HTTP calls are replaced by mocks; routing, JSON checking, budgets and recording are real.
 */
@SpringBootTest(properties = {"cmo2.ai.provider=anthropic", "cmo2.loop.max-pending-drafts=2"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CyclePlanningTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;

    @MockBean
    AnthropicProvider claude;
    @MockBean
    OpenAiProvider openAi;

    private final List<AiRequest> claudeRequests = new ArrayList<>();

    @BeforeEach
    void providers() {
        when(claude.name()).thenReturn("anthropic");
        when(claude.isConfigured()).thenReturn(true);
        when(openAi.name()).thenReturn("openai");
        when(openAi.isConfigured()).thenReturn(true);
        when(claude.generate(any(), anyString())).thenAnswer(inv -> {
            AiRequest r = inv.getArgument(0);
            claudeRequests.add(r);
            return new AiResult("""
                    {"objective":"Book more spring roof surveys","rationale":"Leads are up but few book.",
                     "posts":[{"angle":"Spring check","brief":"Remind homeowners storms loosen tiles; offer the free survey."},
                              {"angle":"Before and after","brief":"Show a repaired roof; invite survey bookings."}]}""",
                    900, 150, "claude-opus-5-5");
        });
        when(openAi.generate(any(), anyString())).thenAnswer(inv -> {
            AiRequest r = inv.getArgument(0);
            return new AiResult("TRANSLATION".equals(r.kind()) ? "Revisión de primavera…" : "Spring storms loosen tiles…",
                    300, 120, "gpt-5");
        });
    }

    private String register() throws Exception {
        MvcResult res = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                        {"email":"plan.%s@example.com","password":"superSecret123","fullName":"Owner","organizationName":"Agency"}
                        """.formatted(UUID.randomUUID())))
                .andExpect(status().isCreated()).andReturn();
        String token = json.readTree(res.getResponse().getContentAsString()).get("accessToken").asText();
        mvc.perform(post("/api/billing/simulate-activation").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"plan\":\"PRO\"}")).andExpect(status().isOk());
        return token;
    }

    private long customer(String token) throws Exception {
        MvcResult res = mvc.perform(post("/api/companies").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Acme Roofing\"}"))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(res.getResponse().getContentAsString()).get("id").asLong();
    }

    private JsonNode runCycle(String token, long company, int n) throws Exception {
        mvc.perform(post("/api/companies/" + company + "/loop/cycles").header("Authorization", "Bearer " + token))
                .andExpect(status().isAccepted());
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(150)).untilAsserted(() -> {
            JsonNode cycles = read(mvc.perform(get("/api/companies/" + company + "/loop/cycles")
                    .header("Authorization", "Bearer " + token)).andReturn());
            assertThat(cycles).hasSize(n);
            assertThat(cycles.get(0).get("status").asText()).isEqualTo("COMPLETED");
        });
        JsonNode cycles = read(mvc.perform(get("/api/companies/" + company + "/loop/cycles")
                .header("Authorization", "Bearer " + token)).andReturn());
        JsonNode detail = read(mvc.perform(get("/api/loop/cycles/" + cycles.get(0).get("id").asLong())
                .header("Authorization", "Bearer " + token)).andReturn());
        for (JsonNode stage : detail.get("stages")) {
            if (stage.get("stageNo").asInt() == 1) return json.readTree(stage.get("dataJson").asText());
        }
        throw new AssertionError("no stage 1 output");
    }

    private JsonNode read(MvcResult res) throws Exception {
        return json.readTree(res.getResponse().getContentAsString());
    }

    @Test
    void oneCyclePlansWithClaudeAndDraftsWithOpenAi() throws Exception {
        String token = register();
        long company = customer(token);

        JsonNode plan = runCycle(token, company, 1);
        assertThat(plan.get("source").asText()).isEqualTo("ai:cycle-plan");
        assertThat(plan.get("objective").asText()).isEqualTo("Book more spring roof surveys");
        assertThat(plan.get("drafts").get("created")).hasSize(1);

        // Claude was asked for structured output, with the customer's identity in the system prompt.
        assertThat(claudeRequests).hasSize(1);
        assertThat(claudeRequests.get(0).jsonSchema()).isNotNull();
        assertThat(claudeRequests.get(0).systemPrompt()).contains("CUSTOMER IDENTITY");
        assertThat(claudeRequests.get(0).userPrompt()).contains("This is the first cycle.");

        // Every call is on record against the customer, with the model that made it.
        JsonNode calls = read(mvc.perform(get("/api/companies/" + company + "/ai-interactions")
                .header("Authorization", "Bearer " + token)).andReturn());
        List<String> models = new ArrayList<>();
        calls.forEach(c -> models.add(c.get("model").asText() + "/" + c.get("kind").asText() + "/" + c.get("stage").asText()));
        assertThat(models).containsExactlyInAnyOrder(
                "claude-opus-5-5/CYCLE_PLAN/INNOVATION", "gpt-5/SOCIAL_MEDIA/INNOVATION", "gpt-5/TRANSLATION/INNOVATION");

        // The draft (and its translation) wait for approval; nothing was published.
        JsonNode queue = read(mvc.perform(get("/api/approvals?companyId=" + company)
                .header("Authorization", "Bearer " + token)).andReturn());
        assertThat(queue).hasSize(2);
        assertThat(queue.get(0).get("asset").get("status").asText()).isEqualTo("PENDING_APPROVAL");

        // With two drafts already waiting (the limit here), the next cycle plans but doesn't draft.
        JsonNode second = runCycle(token, company, 2);
        assertThat(second.get("drafts").get("created")).isEmpty();
        assertThat(second.get("drafts").get("skipped").asText()).contains("2 drafts are already waiting");
        assertThat(claudeRequests.get(1).userPrompt()).contains("Drafts already waiting for the owner's approval: 2");
    }

    @Test
    void whenAiIsUnavailableTheCycleStillRunsOnTheFixedPlan() throws Exception {
        when(claude.generate(any(), anyString())).thenThrow(new ApiException(HttpStatus.TOO_MANY_REQUESTS, "budget reached"));
        String token = register();
        long company = customer(token);

        JsonNode plan = runCycle(token, company, 1);

        assertThat(plan.get("source").asText()).isEqualTo("fixed:innovation");
        assertThat(plan.get("aiSkipped").asText()).contains("budget reached");
    }
}
