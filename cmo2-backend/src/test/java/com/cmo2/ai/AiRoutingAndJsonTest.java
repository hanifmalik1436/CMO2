package com.cmo2.ai;

import com.cmo2.ai.AiMessages.AiRequest;
import com.cmo2.ai.AiMessages.AiResult;
import com.cmo2.ai.provider.AiProvider;
import com.cmo2.shared.exceptions.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class AiRoutingAndJsonTest {

    /** A provider that answers from a queue and remembers what it was asked. */
    static final class FakeProvider implements AiProvider {
        final String name;
        final boolean configured;
        final Deque<String> answers = new ArrayDeque<>();
        final List<AiRequest> requests = new ArrayList<>();

        FakeProvider(String name, boolean configured) {
            this.name = name;
            this.configured = configured;
        }

        public String name() { return name; }
        public boolean isConfigured() { return configured; }
        public AiResult generate(AiRequest request, String modelId) {
            requests.add(request);
            return new AiResult(answers.isEmpty() ? "ok" : answers.pop(), 10, 5, modelId);
        }
    }

    private static AiRouter router(String provider, List<AiProvider> providers, String strategyRoute) {
        return new AiRouter(providers, provider, strategyRoute, "", "",
                "claude-opus-5-5", "claude-opus-5-5", "claude-haiku-5-5", "gpt-5", "gpt-5", "gpt-5-mini");
    }

    private final FakeProvider stub = new FakeProvider("stub", true);
    private final FakeProvider claude = new FakeProvider("anthropic", true);
    private final FakeProvider openAi = new FakeProvider("openai", true);

    @Test
    void strategyGoesToClaudeAndContentToOpenAi() {
        AiRouter r = router("anthropic", List.of(stub, claude, openAi), "");
        assertThat(r.route(AiModelTier.STRATEGY).provider()).isSameAs(claude);
        assertThat(r.route(AiModelTier.STRATEGY).model()).isEqualTo("claude-opus-5-5");
        assertThat(r.route(AiModelTier.GENERATION).provider()).isSameAs(openAi);
        assertThat(r.route(AiModelTier.TRANSLATION).model()).isEqualTo("gpt-5-mini");
    }

    @Test
    void withoutAnOpenAiKeyEverythingStaysOnClaude() {
        AiRouter r = router("anthropic", List.of(stub, claude, new FakeProvider("openai", false)), "");
        assertThat(r.route(AiModelTier.GENERATION).provider()).isSameAs(claude);
        assertThat(r.route(AiModelTier.TRANSLATION).model()).isEqualTo("claude-haiku-5-5");
    }

    @Test
    void stubModeAndExplicitRoutesWin() {
        assertThat(router("stub", List.of(stub, claude, openAi), "").route(AiModelTier.GENERATION).provider()).isSameAs(stub);
        assertThat(router("anthropic", List.of(stub, claude, openAi), "openai").route(AiModelTier.STRATEGY).provider()).isSameAs(openAi);
    }

    @Test
    void aRouteToAProviderWithoutAKeyFailsClearly() {
        AiRouter r = router("anthropic", List.of(stub, new FakeProvider("anthropic", false), openAi), "anthropic");
        assertThatThrownBy(() -> r.route(AiModelTier.STRATEGY))
                .isInstanceOf(ApiException.class).hasMessageContaining("no API key");
    }

    // ---- generateJson ----------------------------------------------------------------------

    static final Map<String, Object> SCHEMA = JsonSchemas.object(Map.of(
            "objective", JsonSchemas.string(20, null),
            "posts", JsonSchemas.array(JsonSchemas.object(Map.of("angle", JsonSchemas.string(50, null))), 2, null)));

    private AiService service(FakeProvider provider) {
        return new AiService(router("anthropic", List.of(stub, provider), "anthropic"),
                mock(CostGuard.class), mock(TokenAccountant.class), mock(AiInteractionRecorder.class), 0);
    }

    private static AiRequest request() {
        return new AiRequest("sys", "plan it", AiModelTier.STRATEGY, "CYCLE_PLAN", 1000);
    }

    @Test
    void anAnswerThatBreaksTheSchemaGetsOneRepairAttempt() {
        claude.answers.add("{\"objective\":\"Grow leads\",\"posts\":[{\"angle\":\"a\"},{\"angle\":\"b\"},{\"angle\":\"c\"}]}");
        claude.answers.add("```json\n{\"objective\":\"Grow leads\",\"posts\":[{\"angle\":\"a\"}]}\n```");

        JsonNode plan = service(claude).generateJson(request(), SCHEMA);

        assertThat(plan.path("posts")).hasSize(1);
        assertThat(claude.requests).hasSize(2);
        assertThat(claude.requests.get(0).jsonSchema()).isEqualTo(SCHEMA);
        assertThat(claude.requests.get(1).userPrompt()).contains("more than 2 items");
    }

    @Test
    void twoBadAnswersFailWithTheReason() {
        claude.answers.add("not json");
        claude.answers.add("{\"objective\":\"far too long for twenty characters\",\"posts\":[],\"extra\":1}");

        assertThatThrownBy(() -> service(claude).generateJson(request(), SCHEMA))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY))
                .hasMessageContaining("longer than 20")
                .hasMessageContaining("extra: not allowed");
    }

    @Test
    void validatesAndStripsLimitsForProviders() throws Exception {
        ObjectMapper json = new ObjectMapper();
        assertThat(JsonSchemas.validate(SCHEMA, json.readTree("{\"objective\":\"x\",\"posts\":[]}"))).isEmpty();
        assertThat(JsonSchemas.validate(SCHEMA, json.readTree("{\"posts\":[{\"angle\":3}]}")))
                .contains("$.objective: missing", "$.posts[0].angle: expected string");

        Map<String, Object> sent = JsonSchemas.forProvider(SCHEMA);
        assertThat(sent.toString()).doesNotContain("maxLength").doesNotContain("maxItems").contains("additionalProperties=false");
    }
}
