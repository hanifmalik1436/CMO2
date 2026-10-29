package com.cmo2.ai;

import com.cmo2.ai.provider.AiProvider;
import com.cmo2.shared.exceptions.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Which provider and model handle each kind of task. By default (CLAUDE.md §10) strategy goes to
 * Claude, and content generation and translation go to OpenAI when an OpenAI key is set (else
 * Claude). {@code cmo2.ai.route.<tier>} overrides a tier; {@code cmo2.ai.provider=stub} keeps every
 * tier on the demo provider (development and tests).
 */
@Component
public class AiRouter {

    public record Route(AiProvider provider, String model) {}

    private final List<AiProvider> providers;
    private final String defaultProvider;
    private final Map<AiModelTier, String> explicitRoutes = new EnumMap<>(AiModelTier.class);
    private final Map<String, Map<AiModelTier, String>> models;

    public AiRouter(List<AiProvider> providerBeans,
                    @Value("${cmo2.ai.provider:stub}") String defaultProvider,
                    @Value("${cmo2.ai.route.strategy:}") String strategyRoute,
                    @Value("${cmo2.ai.route.generation:}") String generationRoute,
                    @Value("${cmo2.ai.route.translation:}") String translationRoute,
                    @Value("${cmo2.ai.model.strategy:claude-opus-5-5}") String claudeStrategy,
                    @Value("${cmo2.ai.model.generation:claude-opus-5-5}") String claudeGeneration,
                    @Value("${cmo2.ai.model.translation:claude-haiku-5-5}") String claudeTranslation,
                    @Value("${cmo2.ai.openai.model.strategy:gpt-5}") String openAiStrategy,
                    @Value("${cmo2.ai.openai.model.generation:gpt-5}") String openAiGeneration,
                    @Value("${cmo2.ai.openai.model.translation:gpt-5-mini}") String openAiTranslation) {
        this.providers = List.copyOf(providerBeans);
        this.defaultProvider = defaultProvider == null || defaultProvider.isBlank() ? "stub" : defaultProvider.trim();
        putIfSet(AiModelTier.STRATEGY, strategyRoute);
        putIfSet(AiModelTier.GENERATION, generationRoute);
        putIfSet(AiModelTier.TRANSLATION, translationRoute);
        Map<AiModelTier, String> claude = Map.of(AiModelTier.STRATEGY, claudeStrategy,
                AiModelTier.GENERATION, claudeGeneration, AiModelTier.TRANSLATION, claudeTranslation);
        this.models = Map.of(
                "anthropic", claude,
                "stub", claude,
                "openai", Map.of(AiModelTier.STRATEGY, openAiStrategy,
                        AiModelTier.GENERATION, openAiGeneration, AiModelTier.TRANSLATION, openAiTranslation));
    }

    public Route route(AiModelTier tier) {
        String name = providerName(tier);
        AiProvider provider = provider(name);
        if (provider == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Unknown AI provider '" + name + "' for " + tier);
        }
        if (!provider.isConfigured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "AI provider '" + name + "' handles " + tier + " but has no API key configured");
        }
        String model = models.getOrDefault(name, models.get("anthropic")).get(tier);
        return new Route(provider, model);
    }

    String providerName(AiModelTier tier) {
        String explicit = explicitRoutes.get(tier);
        if (explicit != null) return explicit;
        if ("stub".equals(defaultProvider)) return "stub";
        boolean claude = configured("anthropic");
        boolean openAi = configured("openai");
        return switch (tier) {
            case STRATEGY -> claude ? "anthropic" : defaultProvider;
            case GENERATION, TRANSLATION -> openAi ? "openai" : claude ? "anthropic" : defaultProvider;
        };
    }

    private boolean configured(String name) {
        AiProvider p = provider(name);
        return p != null && p.isConfigured();
    }

    /** Looked up per call rather than at startup, so a provider is always asked its current state. */
    private AiProvider provider(String name) {
        for (AiProvider p : providers) {
            if (name.equals(p.name())) return p;
        }
        return null;
    }

    private void putIfSet(AiModelTier tier, String route) {
        if (route != null && !route.isBlank()) explicitRoutes.put(tier, route.trim());
    }
}
