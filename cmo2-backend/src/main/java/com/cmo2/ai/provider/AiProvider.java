package com.cmo2.ai.provider;

import com.cmo2.ai.AiMessages.AiRequest;
import com.cmo2.ai.AiMessages.AiResult;
import com.cmo2.shared.exceptions.ApiException;
import org.springframework.http.HttpStatus;

/**
 * A pluggable AI backend. {@link com.cmo2.ai.AiRouter} picks one per task (model tier). NON-NEGOTIABLE RULE #8: modules never call a provider
 * directly — everything goes through {@link com.cmo2.ai.AiService}.
 */
public interface AiProvider {

    String name();

    AiResult generate(AiRequest request, String modelId);

    /**
     * Speech to text. Only providers that can hear audio override this; the result's text is the
     * transcript.
     */
    default AiResult transcribe(byte[] audio, String filename, String mimeType, String modelId) {
        throw new ApiException(HttpStatus.NOT_IMPLEMENTED, "AI provider '" + name() + "' can't transcribe audio");
    }

    /** Whether this provider has what it needs (e.g. an API key) to take calls. */
    default boolean isConfigured() {
        return true;
    }
}
