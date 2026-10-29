package com.cmo2.ai.provider;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.cmo2.ai.AiMessages.AiRequest;
import com.cmo2.ai.AiMessages.AiResult;
import com.cmo2.ai.AiModelTier;
import com.cmo2.ai.JsonSchemas;
import com.cmo2.shared.exceptions.ApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Claude via the official Anthropic Java SDK. Selected by {@link com.cmo2.ai.AiRouter} (strategy by
 * default) when {@code cmo2.ai.api-key} is set; models per tier come from {@code cmo2.ai.model.*}.
 *
 * <ul>
 *   <li>Effort is set per task: high for strategy, medium for generation, low for translation
 *       (Claude Opus 5.5 always thinks and otherwise defaults to medium).</li>
 *   <li>A JSON schema on the request becomes {@code output_config.format} (structured output).</li>
 *   <li>Server-side refusal fallback is on by default ({@code cmo2.ai.anthropic.refusal-fallback}): a
 *       request a safety classifier declines is retried by the API on another model. A refusal that
 *       still comes back is raised as an error rather than returned as empty text.</li>
 * </ul>
 */
@Component
public class AnthropicProvider implements AiProvider {

    /** Thinking counts toward max_tokens, so small limits would cut answers off. */
    static final long MIN_MAX_TOKENS = 16_000;
    static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";

    private final String apiKey;
    private final String baseUrl;
    private final boolean refusalFallback;
    private volatile AnthropicClient client;

    @Autowired
    public AnthropicProvider(@Value("${cmo2.ai.api-key:}") String apiKey,
                             @Value("${cmo2.ai.anthropic.refusal-fallback:true}") boolean refusalFallback) {
        this(apiKey, null, refusalFallback);
    }

    AnthropicProvider(String apiKey, String baseUrl, boolean refusalFallback) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.refusalFallback = refusalFallback;
    }

    @Override
    public String name() {
        return "anthropic";
    }

    @Override
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public AiResult generate(AiRequest request, String modelId) {
        Message message = client().messages().create(params(request, modelId));

        if (message.stopReason().filter(StopReason.REFUSAL::equals).isPresent()) {
            String category = message.stopDetails().flatMap(d -> d.category()).map(Object::toString).orElse("unspecified");
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "Claude declined this request (" + category + ")");
        }

        String text = message.content().stream()
                .flatMap(block -> block.text().stream())
                .map(t -> t.text())
                .collect(Collectors.joining("\n"));

        long inTokens = message.usage().inputTokens();
        long outTokens = message.usage().outputTokens();
        return new AiResult(text, inTokens, outTokens, message.model().toString());
    }

    MessageCreateParams params(AiRequest request, String modelId) {
        OutputConfig.Builder output = OutputConfig.builder().effort(effort(request.tier()));
        if (request.jsonSchema() != null) {
            Map<String, JsonValue> schema = new LinkedHashMap<>();
            JsonSchemas.forProvider(request.jsonSchema()).forEach((k, v) -> schema.put(k, JsonValue.from(v)));
            output.format(JsonOutputFormat.builder()
                    .schema(JsonOutputFormat.Schema.builder().putAllAdditionalProperties(schema).build())
                    .build());
        }
        MessageCreateParams.Builder builder = MessageCreateParams.builder()
                .model(modelId)
                .maxTokens(Math.max(MIN_MAX_TOKENS, request.maxTokens()))
                .outputConfig(output.build())
                .addUserMessage(request.userPrompt());
        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            builder.system(request.systemPrompt());
        }
        if (refusalFallback) {
            // SDK 2.34 has no typed field for this beta; the API takes it as a body property plus header.
            builder.putAdditionalHeader("anthropic-beta", FALLBACK_BETA)
                    .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
        }
        return builder.build();
    }

    static OutputConfig.Effort effort(AiModelTier tier) {
        return switch (tier) {
            case STRATEGY -> OutputConfig.Effort.HIGH;
            case GENERATION -> OutputConfig.Effort.MEDIUM;
            case TRANSLATION -> OutputConfig.Effort.LOW;
        };
    }

    private AnthropicClient client() {
        AnthropicClient local = client;
        if (local == null) {
            synchronized (this) {
                local = client;
                if (local == null) {
                    if (!isConfigured()) {
                        throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                                "AI provider 'anthropic' selected but cmo2.ai.api-key is not set");
                    }
                    AnthropicOkHttpClient.Builder b = AnthropicOkHttpClient.builder().apiKey(apiKey);
                    if (baseUrl != null) b.baseUrl(baseUrl);
                    local = b.build();
                    client = local;
                }
            }
        }
        return local;
    }
}
