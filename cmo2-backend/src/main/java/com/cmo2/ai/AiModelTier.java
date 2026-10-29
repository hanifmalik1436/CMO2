package com.cmo2.ai;

/**
 * The kinds of AI task {@link AiService} routes ({@link AiRouter}): strategy to Claude, content
 * generation and translation to OpenAI when configured. Models per tier and provider are set in
 * application.properties ({@code cmo2.ai.model.*}, {@code cmo2.ai.openai.model.*}).
 */
public enum AiModelTier {
    GENERATION,   // content drafts (OpenAI gpt-5, or Claude claude-opus-5-5)
    TRANSLATION,  // bulk EN→ES translation (OpenAI gpt-5-mini, or Claude claude-haiku-5-5)
    STRATEGY      // cycle plans and strategist sessions (Claude claude-opus-5-5)
}
