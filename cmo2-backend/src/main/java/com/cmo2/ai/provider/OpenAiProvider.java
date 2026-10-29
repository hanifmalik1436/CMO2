package com.cmo2.ai.provider;

import com.cmo2.ai.AiMessages.AiRequest;
import com.cmo2.ai.AiMessages.AiResult;
import com.cmo2.ai.JsonSchemas;
import com.cmo2.shared.exceptions.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI's Chat Completions API, for content generation and translation by default (CLAUDE.md §10).
 * Selected by {@link com.cmo2.ai.AiRouter} when {@code cmo2.ai.openai.api-key} is set. A JSON schema
 * on the request becomes {@code response_format: json_schema} in strict mode.
 */
@Component
public class OpenAiProvider implements AiProvider {

    private final String apiKey;
    private final RestClient client;

    @Autowired
    public OpenAiProvider(@Value("${cmo2.ai.openai.api-key:}") String apiKey,
                          @Value("${cmo2.ai.openai.base-url:https://api.openai.com/v1}") String baseUrl) {
        this(apiKey, RestClient.builder().baseUrl(baseUrl));
    }

    OpenAiProvider(String apiKey, RestClient.Builder builder) {
        this.apiKey = apiKey;
        this.client = builder.build();
    }

    @Override
    public String name() {
        return "openai";
    }

    @Override
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public AiResult generate(AiRequest request, String modelId) {
        if (!isConfigured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "AI provider 'openai' selected but cmo2.ai.openai.api-key is not set");
        }
        JsonNode response;
        try {
            response = client.post().uri("/chat/completions")
                    .headers(h -> h.setBearerAuth(apiKey))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body(request, modelId))
                    .retrieve().body(JsonNode.class);
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            throw new ApiException(status == 429 ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.BAD_GATEWAY,
                    "OpenAI returned " + status + (status == 401 ? ": the API key was rejected" : ""));
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Could not reach OpenAI: " + e.getMessage());
        }
        if (response == null) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "OpenAI returned an empty response");
        }
        JsonNode message = response.path("choices").path(0).path("message");
        String refusal = message.path("refusal").asText(null);
        if (refusal != null && !refusal.isBlank()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "OpenAI declined this request: " + refusal);
        }
        JsonNode usage = response.path("usage");
        return new AiResult(message.path("content").asText(""), usage.path("prompt_tokens").asLong(0),
                usage.path("completion_tokens").asLong(0), response.path("model").asText(modelId));
    }

    Map<String, Object> body(AiRequest request, String modelId) {
        List<Map<String, String>> messages = new ArrayList<>();
        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            messages.add(Map.of("role", "system", "content", request.systemPrompt()));
        }
        messages.add(Map.of("role", "user", "content", request.userPrompt()));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", modelId);
        body.put("messages", messages);
        // Reasoning models count their reasoning in this limit too, so keep it generous.
        body.put("max_completion_tokens", Math.max(16_000, request.maxTokens()));
        if (request.jsonSchema() != null) {
            body.put("response_format", Map.of("type", "json_schema", "json_schema", Map.of(
                    "name", schemaName(request.kind()),
                    "strict", true,
                    "schema", JsonSchemas.forProvider(request.jsonSchema()))));
        }
        return body;
    }

    /** OpenAI schema names allow only letters, digits, underscores and dashes (max 64). */
    static String schemaName(String kind) {
        String name = (kind == null || kind.isBlank() ? "answer" : kind).replaceAll("[^A-Za-z0-9_-]", "_");
        return name.length() <= 64 ? name : name.substring(0, 64);
    }
}
