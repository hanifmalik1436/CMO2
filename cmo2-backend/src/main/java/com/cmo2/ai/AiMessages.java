package com.cmo2.ai;

import java.util.Map;

/** Request/result value types for the AI layer. */
public final class AiMessages {

    private AiMessages() {}

    /**
     * @param kind a hint (asset type name, or "TRANSLATION") the stub provider uses to
     *             shape demo output; real providers fold it into the natural-language prompt.
     * @param jsonSchema when set, the provider is asked for JSON matching it (structured output)
     * @param context the customer's identity block (facts and approved canon). Providers put it first
     *                in the system prompt, before {@code systemPrompt}, and mark it for caching, so it
     *                must hold nothing that changes from call to call. Null when the call isn't for a customer.
     */
    public record AiRequest(String systemPrompt, String userPrompt, AiModelTier tier, String kind, int maxTokens,
                            Map<String, Object> jsonSchema, String context) {

        public AiRequest(String systemPrompt, String userPrompt, AiModelTier tier, String kind, int maxTokens) {
            this(systemPrompt, userPrompt, tier, kind, maxTokens, null, null);
        }

        public AiRequest(String systemPrompt, String userPrompt, AiModelTier tier, String kind, int maxTokens,
                         Map<String, Object> jsonSchema) {
            this(systemPrompt, userPrompt, tier, kind, maxTokens, jsonSchema, null);
        }

        /** The same request, asking for JSON matching {@code schema} (see {@link AiService#generateJson}). */
        public AiRequest withJsonSchema(Map<String, Object> schema) {
            return new AiRequest(systemPrompt, userPrompt, tier, kind, maxTokens, schema, context);
        }

        public AiRequest withUserPrompt(String prompt) {
            return new AiRequest(systemPrompt, prompt, tier, kind, maxTokens, jsonSchema, context);
        }

        public AiRequest withContext(String customerContext) {
            String c = customerContext == null || customerContext.isBlank() ? null : customerContext.trim();
            return new AiRequest(systemPrompt, userPrompt, tier, kind, maxTokens, jsonSchema, c);
        }

        /** The whole system prompt as one text, for providers without separate blocks: the context first, then the task's own. */
        public String fullSystemPrompt() {
            boolean hasContext = context != null && !context.isBlank();
            boolean hasSystem = systemPrompt != null && !systemPrompt.isBlank();
            if (hasContext && hasSystem) return context + "\n\n" + systemPrompt;
            if (hasContext) return context;
            return hasSystem ? systemPrompt : null;
        }
    }

    /**
     * @param inputTokens input tokens read normally, outside the provider's prompt cache
     * @param cacheReadTokens input tokens read from the prompt cache
     * @param cacheWriteTokens input tokens written to the prompt cache (Claude only; OpenAI caches for free)
     */
    public record AiResult(String text, long inputTokens, long outputTokens, String model,
                           long cacheReadTokens, long cacheWriteTokens) {

        public AiResult(String text, long inputTokens, long outputTokens, String model) {
            this(text, inputTokens, outputTokens, model, 0, 0);
        }

        /** Every token the call handled, cached or not. */
        public long totalTokens() {
            return inputTokens + cacheReadTokens + cacheWriteTokens + outputTokens;
        }

        /**
         * What the call counts against the monthly budgets. Cache reads cost a tenth of normal input
         * or less, so they count a tenth; everything else counts in full.
         */
        public long budgetTokens() {
            return inputTokens + cacheWriteTokens + outputTokens + (cacheReadTokens + 9) / 10;
        }
    }
}
