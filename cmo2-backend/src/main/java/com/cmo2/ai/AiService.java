package com.cmo2.ai;

import com.cmo2.ai.AiMessages.AiRequest;
import com.cmo2.ai.AiMessages.AiResult;
import com.cmo2.ai.AiRouter.Route;
import com.cmo2.shared.exceptions.ApiException;
import com.cmo2.shared.security.TenantContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * The single, provider-agnostic entry point for ALL AI calls (NON-NEGOTIABLE RULE #8).
 * Enforces the per-tenant budget via {@link CostGuard} and the per-customer budget, routes each task
 * to its provider and model via {@link AiRouter}, meters tokens via {@link TokenAccountant}, and
 * records every call (prompt and response) via {@link AiInteractionRecorder}. No module ever
 * calls a provider SDK directly.
 */
@Service
public class AiService {

    private static final Logger log = LoggerFactory.getLogger(AiService.class);
    static final int JSON_ATTEMPTS = 2;

    private final AiRouter router;
    private final CostGuard costGuard;
    private final TokenAccountant tokenAccountant;
    private final AiInteractionRecorder interactions;
    private final long companyMonthlyCeiling;
    private final ObjectMapper json = new ObjectMapper();

    public AiService(AiRouter router,
                     CostGuard costGuard,
                     TokenAccountant tokenAccountant,
                     AiInteractionRecorder interactions,
                     @Value("${cmo2.ai.company-monthly-token-ceiling:2000000}") long companyMonthlyCeiling) {
        this.router = router;
        this.costGuard = costGuard;
        this.tokenAccountant = tokenAccountant;
        this.interactions = interactions;
        this.companyMonthlyCeiling = companyMonthlyCeiling;
    }

    /**
     * Budget-checked, metered AI generation. Must be called within a tenant context.
     */
    public AiResult generate(AiRequest request) {
        AiCallScope.Scope scope = AiCallScope.current();
        costGuard.assertWithinBudget();
        if (scope.companyId() != null) {
            assertCompanyWithinBudget(scope.companyId());
        }

        Route route = router.route(request.tier());

        AiResult result = route.provider().generate(request, route.model());

        tokenAccountant.record(result.totalTokens());
        try {
            interactions.record(request, result, scope);
        } catch (RuntimeException e) {
            log.warn("Could not record AI call ({}): {}", request.kind(), e.getMessage());
        }
        log.debug("AI call: provider={} tier={} model={} tokens={}",
                route.provider().name(), request.tier(), result.model(), result.totalTokens());
        return result;
    }

    /**
     * Asks for JSON matching {@code schema} (the provider's structured-output mode gets a copy without
     * length and count limits) and checks the whole schema here. One repair attempt, which names
     * what was wrong; both attempts are metered and recorded like any other call.
     *
     * @throws ApiException 502 if no attempt produced matching JSON
     */
    public JsonNode generateJson(AiRequest request, Map<String, Object> schema) {
        AiRequest structured = request.withJsonSchema(schema);
        String problem = null;
        for (int attempt = 1; attempt <= JSON_ATTEMPTS; attempt++) {
            AiRequest r = problem == null ? structured : structured.withUserPrompt(request.userPrompt()
                    + "\n\nYour previous answer was rejected (" + problem + "). Reply with JSON that matches the schema exactly.");
            AiResult result = generate(r);
            try {
                JsonNode value = json.readTree(stripFences(result.text()));
                List<String> problems = value == null ? List.of("empty answer") : JsonSchemas.validate(schema, value);
                if (problems.isEmpty()) {
                    return value;
                }
                problem = String.join("; ", problems.subList(0, Math.min(5, problems.size())));
            } catch (Exception e) {
                problem = "not valid JSON";
            }
            log.info("AI JSON answer for {} rejected (attempt {}): {}", request.kind(), attempt, problem);
        }
        throw new ApiException(HttpStatus.BAD_GATEWAY, "The AI's answer didn't match the expected format: " + problem);
    }

    /** Typed form of {@link #generateJson(AiRequest, Map)}. */
    public <T> T generateJson(AiRequest request, Map<String, Object> schema, Class<T> type) {
        try {
            return json.treeToValue(generateJson(request, schema), type);
        } catch (JsonProcessingException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "The AI's answer couldn't be read: " + e.getOriginalMessage());
        }
    }

    /** Some models wrap JSON in a ```json fence even in JSON mode. */
    static String stripFences(String text) {
        if (text == null) return "";
        String t = text.strip();
        if (t.startsWith("```")) {
            int firstNewline = t.indexOf('\n');
            int lastFence = t.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                return t.substring(firstNewline + 1, lastFence).strip();
            }
        }
        return t;
    }

    /** A customer's own monthly ceiling, so one customer's loop can't spend the agency's whole budget. */
    private void assertCompanyWithinBudget(Long companyId) {
        if (companyMonthlyCeiling <= 0) return;
        long used = interactions.companyTokensThisMonth(TenantContext.requireTenantId(), companyId);
        if (used >= companyMonthlyCeiling) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "This customer has used its monthly AI budget ("
                    + companyMonthlyCeiling + " tokens). Raise cmo2.ai.company-monthly-token-ceiling to continue.");
        }
    }
}
