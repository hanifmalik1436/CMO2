package com.cmo2.studio;

import com.cmo2.ai.AiMessages.AiRequest;
import com.cmo2.ai.AiMessages.AiResult;
import com.cmo2.ai.provider.AnthropicProvider;
import com.cmo2.ai.provider.OpenAiProvider;
import com.fasterxml.jackson.databind.JsonNode;
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

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The studio with live AI routing: Claude finds the ideas (structured output), OpenAI writes each
 * draft as that format's agent, with the customer's identity in the prompt; and a batch stops cleanly
 * when the customer's AI budget runs out. Only the providers' HTTP calls are mocked.
 */
@SpringBootTest(properties = {"cmo2.ai.provider=anthropic", "cmo2.ai.company-monthly-token-ceiling=2000"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ContentStudioLiveRoutingTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;

    @MockBean
    AnthropicProvider claude;
    @MockBean
    OpenAiProvider openAi;

    private final List<AiRequest> claudeAsked = new CopyOnWriteArrayList<>();
    private final List<AiRequest> openAiAsked = new CopyOnWriteArrayList<>();

    @BeforeEach
    void providers() {
        when(claude.name()).thenReturn("anthropic");
        when(claude.isConfigured()).thenReturn(true);
        when(openAi.name()).thenReturn("openai");
        when(openAi.isConfigured()).thenReturn(true);
        when(claude.generate(any(), anyString())).thenAnswer(inv -> {
            claudeAsked.add(inv.getArgument(0));
            return new AiResult("""
                    {"ideas":[{"type":"INSIGHT","title":"Quotes stall because they show cost, not savings",
                      "summary":"Leading with the monthly saving turns a cost into an investment.",
                      "hooks":["Your quote is losing you the sale."],"quote":"The quote shows a price but not the monthly saving.",
                      "viralityScore":72,"viralityReason":"Specific number: a concrete saving stops the scroll."}]}""",
                    800, 200, "claude-opus-5-5");
        });
        when(openAi.generate(any(), anyString())).thenAnswer(inv -> {
            openAiAsked.add(inv.getArgument(0));
            return new AiResult("Your quote is losing you the sale. Lead with the monthly saving.", 500, 100, "gpt-5");
        });
    }

    private String register() throws Exception {
        MvcResult res = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                        {"email":"live.%s@example.com","password":"superSecret123","fullName":"Owner","organizationName":"Agency"}
                        """.formatted(UUID.randomUUID())))
                .andExpect(status().isCreated()).andReturn();
        String token = read(res).get("accessToken").asText();
        mvc.perform(post("/api/billing/simulate-activation").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"plan\":\"PRO\"}")).andExpect(status().isOk());
        return token;
    }

    private JsonNode read(MvcResult res) throws Exception {
        return json.readTree(res.getResponse().getContentAsString());
    }

    private long[] customerWithIdea(String token) throws Exception {
        long company = read(mvc.perform(post("/api/companies").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Northwind Solar\"}"))
                .andExpect(status().isCreated()).andReturn()).get("id").asLong();
        long source = read(mvc.perform(post("/api/companies/" + company + "/studio/sources").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                                "title", "Podcast", "kind", "TRANSCRIPT", "body", ContentStudioFlowTest.TRANSCRIPT))))
                .andExpect(status().isCreated()).andReturn()).get("source").get("id").asLong();
        // Every new source is searched for ideas in the background.
        JsonNode[] detail = new JsonNode[1];
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(100)).untilAsserted(() -> {
            detail[0] = read(mvc.perform(get("/api/studio/sources/" + source).header("Authorization", "Bearer " + token)).andReturn());
            assertThat(detail[0].get("source").get("status").asText()).isEqualTo("READY");
        });
        long idea = detail[0].get("ideas").get(0).get("id").asLong();
        return new long[] {company, source, idea};
    }

    private JsonNode runBatch(String token, long company, String body) throws Exception {
        long id = read(mvc.perform(post("/api/companies/" + company + "/studio/batches").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isAccepted()).andReturn()).get("id").asLong();
        JsonNode[] last = new JsonNode[1];
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(150)).untilAsserted(() -> {
            last[0] = read(mvc.perform(get("/api/studio/batches/" + id).header("Authorization", "Bearer " + token)).andReturn());
            assertThat(last[0].get("status").asText()).isNotEqualTo("RUNNING");
        });
        return last[0];
    }

    @Test
    void claudeFindsTheIdeasAndEachFormatAgentWritesWithOpenAi() throws Exception {
        String token = register();
        long[] ids = customerWithIdea(token);

        AiRequest ideasAsk = claudeAsked.get(0);
        assertThat(ideasAsk.kind()).isEqualTo("CONTENT_IDEAS");
        assertThat(ideasAsk.jsonSchema()).isNotNull();
        assertThat(ideasAsk.userPrompt()).contains("DATA: source material").contains("stop replying");
        assertThat(ideasAsk.context()).startsWith("CUSTOMER IDENTITY");
        assertThat(ideasAsk.systemPrompt()).doesNotContain("CUSTOMER IDENTITY");

        JsonNode batch = runBatch(token, ids[0], """
                {"sourceId":%d,"ideaIds":[%d],"formats":["LINKEDIN_POST"],"draftsPerFormat":1}""".formatted(ids[1], ids[2]));
        assertThat(batch.get("status").asText()).isEqualTo("COMPLETED");

        AiRequest draftAsk = openAiAsked.get(0);
        assertThat(draftAsk.kind()).isEqualTo("STUDIO:LINKEDIN_POST");
        assertThat(draftAsk.systemPrompt())
                .contains("You are the LinkedIn specialist")
                .contains("before 'see more'")          // the LinkedIn agent's own instructions
                .contains("Never exceed 3000 characters");
        // The same customer context opens both calls, so the providers can cache it.
        assertThat(draftAsk.context()).isEqualTo(ideasAsk.context());
        assertThat(draftAsk.userPrompt()).contains("Title: Quotes stall because they show cost, not savings");

        JsonNode queue = read(mvc.perform(get("/api/approvals?companyId=" + ids[0]).header("Authorization", "Bearer " + token)).andReturn());
        assertThat(queue.get(0).get("asset").get("contentJson").asText()).contains("Lead with the monthly saving");
    }

    @Test
    void aBatchStopsCleanlyWhenTheCustomersBudgetRunsOut() throws Exception {
        String token = register();
        long[] ids = customerWithIdea(token); // 1,000 tokens used of 2,000

        // 3 pieces of 600 tokens: the third is refused by the per-customer ceiling.
        JsonNode batch = runBatch(token, ids[0], """
                {"sourceId":%d,"ideaIds":[%d],"formats":["X_POST","THREADS_POST","FACEBOOK_POST"],"draftsPerFormat":1}"""
                .formatted(ids[1], ids[2]));

        assertThat(batch.get("status").asText()).isEqualTo("PARTIAL");
        assertThat(batch.get("completed").asInt()).isEqualTo(2);
        assertThat(batch.get("failed").asInt()).isEqualTo(1);
        assertThat(batch.get("note").asText()).startsWith("Stopped early").contains("monthly AI budget");
    }
}
