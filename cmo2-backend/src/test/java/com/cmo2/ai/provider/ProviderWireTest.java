package com.cmo2.ai.provider;

import com.cmo2.ai.AiMessages.AiRequest;
import com.cmo2.ai.AiMessages.AiResult;
import com.cmo2.ai.AiModelTier;
import com.cmo2.ai.JsonSchemas;
import com.cmo2.shared.exceptions.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** What each provider actually sends and how it reads the answer. */
class ProviderWireTest {

    private static final Map<String, Object> SCHEMA = JsonSchemas.object(Map.of(
            "objective", JsonSchemas.string(300, "One sentence")));
    private final ObjectMapper json = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    // ---- OpenAI ----------------------------------------------------------------------------

    @Test
    void openAiGetsAStrictJsonSchemaWithoutLocalOnlyLimits() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://openai.test/v1");
        MockRestServiceServer mock = MockRestServiceServer.bindTo(builder).build();
        mock.expect(requestTo("https://openai.test/v1/chat/completions"))
                .andExpect(header("Authorization", "Bearer sk-test"))
                .andExpect(jsonPath("$.model").value("gpt-5"))
                .andExpect(jsonPath("$.messages[0].role").value("system"))
                .andExpect(jsonPath("$.messages[1].content").value("plan it"))
                .andExpect(jsonPath("$.max_completion_tokens").value(16000))
                .andExpect(jsonPath("$.response_format.type").value("json_schema"))
                .andExpect(jsonPath("$.response_format.json_schema.name").value("CYCLE_PLAN"))
                .andExpect(jsonPath("$.response_format.json_schema.strict").value(true))
                .andExpect(jsonPath("$.response_format.json_schema.schema.additionalProperties").value(false))
                .andExpect(jsonPath("$.response_format.json_schema.schema.properties.objective.maxLength").doesNotExist())
                .andRespond(withSuccess("""
                        {"model":"gpt-5-2026-08-07","choices":[{"message":{"content":"{\\"objective\\":\\"Grow\\"}","refusal":null}}],
                         "usage":{"prompt_tokens":120,"completion_tokens":30}}""", MediaType.APPLICATION_JSON));

        AiResult result = new OpenAiProvider("sk-test", builder).generate(
                new AiRequest("sys", "plan it", AiModelTier.STRATEGY, "CYCLE_PLAN", 4096).withJsonSchema(SCHEMA), "gpt-5");

        assertThat(result.text()).isEqualTo("{\"objective\":\"Grow\"}");
        assertThat(result.inputTokens()).isEqualTo(120);
        assertThat(result.outputTokens()).isEqualTo(30);
        assertThat(result.model()).isEqualTo("gpt-5-2026-08-07");
        mock.verify();
    }

    @Test
    void openAiRefusalsAndRejectedKeysAreErrors() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://openai.test/v1");
        MockRestServiceServer mock = MockRestServiceServer.bindTo(builder).build();
        mock.expect(requestTo("https://openai.test/v1/chat/completions")).andRespond(withSuccess(
                "{\"choices\":[{\"message\":{\"content\":null,\"refusal\":\"I can't help with that.\"}}]}", MediaType.APPLICATION_JSON));
        mock.expect(requestTo("https://openai.test/v1/chat/completions")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        OpenAiProvider provider = new OpenAiProvider("sk-test", builder);
        AiRequest req = new AiRequest(null, "x", AiModelTier.GENERATION, "BLOG_POST", 100);

        assertThatThrownBy(() -> provider.generate(req, "gpt-5")).isInstanceOf(ApiException.class).hasMessageContaining("declined");
        assertThatThrownBy(() -> provider.generate(req, "gpt-5")).isInstanceOf(ApiException.class).hasMessageContaining("API key was rejected");
    }

    // ---- Anthropic (real SDK against a local fake API) -------------------------------------

    private String startFakeAnthropic(String responseBody, AtomicReference<JsonNode> body, AtomicReference<String> beta)
            throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            body.set(json.readTree(exchange.getRequestBody().readAllBytes()));
            beta.set(exchange.getRequestHeaders().getFirst("anthropic-beta"));
            byte[] out = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static String message(String stopReason, String text) {
        return """
                {"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5",
                 "content":[{"type":"thinking","thinking":"","signature":"sig"},{"type":"text","text":%s}],
                 "stop_reason":"%s","stop_sequence":null,
                 "usage":{"input_tokens":200,"output_tokens":40}}""".formatted(new ObjectMapper().valueToTree(text).toString(), stopReason);
    }

    @Test
    void claudeGetsEffortStructuredOutputAndTheRefusalFallback() throws Exception {
        AtomicReference<JsonNode> body = new AtomicReference<>();
        AtomicReference<String> beta = new AtomicReference<>();
        String base = startFakeAnthropic(message("end_turn", "{\"objective\":\"Grow\"}"), body, beta);

        AiResult result = new AnthropicProvider("sk-ant-test", base, true).generate(
                new AiRequest("sys", "plan it", AiModelTier.STRATEGY, "CYCLE_PLAN", 4096).withJsonSchema(SCHEMA), "claude-opus-5-5");

        JsonNode sent = body.get();
        assertThat(sent.path("model").asText()).isEqualTo("claude-opus-5-5");
        assertThat(sent.path("max_tokens").asLong()).isEqualTo(16000);
        assertThat(sent.path("output_config").path("effort").asText()).isEqualTo("high");
        assertThat(sent.path("output_config").path("format").path("type").asText()).isEqualTo("json_schema");
        assertThat(sent.path("output_config").path("format").path("schema").path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(sent.path("output_config").path("format").path("schema").path("properties").path("objective").has("maxLength")).isFalse();
        assertThat(sent.path("fallbacks").asText()).isEqualTo("default");
        assertThat(beta.get()).contains("server-side-fallback-2026-07-01");

        assertThat(result.text()).isEqualTo("{\"objective\":\"Grow\"}"); // thinking blocks are left out
        assertThat(result.inputTokens()).isEqualTo(200);
        assertThat(result.model()).isEqualTo("claude-opus-5-5");
    }

    @Test
    void translationRunsAtLowEffortAndTheFallbackCanBeTurnedOff() throws Exception {
        AtomicReference<JsonNode> body = new AtomicReference<>();
        AtomicReference<String> beta = new AtomicReference<>();
        String base = startFakeAnthropic(message("end_turn", "Hola"), body, beta);

        new AnthropicProvider("sk-ant-test", base, false).generate(
                new AiRequest(null, "Hello", AiModelTier.TRANSLATION, "TRANSLATION", 4096), "claude-haiku-5-5");

        assertThat(body.get().path("output_config").path("effort").asText()).isEqualTo("low");
        assertThat(body.get().has("fallbacks")).isFalse();
        assertThat(body.get().path("output_config").has("format")).isFalse();
    }

    @Test
    void aRefusalIsAnErrorNotEmptyText() throws Exception {
        String base = startFakeAnthropic(message("refusal", ""), new AtomicReference<>(), new AtomicReference<>());

        assertThatThrownBy(() -> new AnthropicProvider("sk-ant-test", base, true).generate(
                new AiRequest(null, "x", AiModelTier.GENERATION, "BLOG_POST", 100), "claude-opus-5-5"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Claude declined");
    }
}
