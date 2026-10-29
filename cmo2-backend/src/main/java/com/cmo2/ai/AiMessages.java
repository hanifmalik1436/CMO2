package com.cmo2.ai;

import java.util.Map;

/** Request/result value types for the AI layer. */
public final class AiMessages {

    private AiMessages() {}

    /**
     * @param kind a hint (asset type name, or "TRANSLATION") the stub provider uses to
     *             shape demo output; real providers fold it into the natural-language prompt.
     * @param jsonSchema when set, the provider is asked for JSON matching it (structured output)
     */
    public record AiRequest(String systemPrompt, String userPrompt, AiModelTier tier, String kind, int maxTokens,
                            Map<String, Object> jsonSchema) {

        public AiRequest(String systemPrompt, String userPrompt, AiModelTier tier, String kind, int maxTokens) {
            this(systemPrompt, userPrompt, tier, kind, maxTokens, null);
        }

        /** The same request, asking for JSON matching {@code schema} (see {@link AiService#generateJson}). */
        public AiRequest withJsonSchema(Map<String, Object> schema) {
            return new AiRequest(systemPrompt, userPrompt, tier, kind, maxTokens, schema);
        }

        public AiRequest withUserPrompt(String prompt) {
            return new AiRequest(systemPrompt, prompt, tier, kind, maxTokens, jsonSchema);
        }
    }

    public record AiResult(String text, long inputTokens, long outputTokens, String model) {
        public long totalTokens() {
            return inputTokens + outputTokens;
        }
    }
}
