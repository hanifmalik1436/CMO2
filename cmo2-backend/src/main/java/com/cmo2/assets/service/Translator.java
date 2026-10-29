package com.cmo2.assets.service;

import com.cmo2.ai.AiMessages.AiResult;
import com.cmo2.ai.AiService;
import com.cmo2.ai.PromptRegistry;
import com.cmo2.assets.domain.Asset;
import com.cmo2.assets.domain.AssetStatus;
import com.cmo2.assets.domain.Language;
import com.cmo2.assets.repository.AssetRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Auto-translates a generated English asset to Spanish via the AI translation tier,
 * storing the result as a linked sibling asset (the bilingual half of the pipeline).
 */
@Service
public class Translator {

    private final AiService aiService;
    private final PromptRegistry prompts;
    private final AssetRepository assetRepository;
    private final ObjectMapper objectMapper;

    public Translator(AiService aiService, PromptRegistry prompts,
                      AssetRepository assetRepository, ObjectMapper objectMapper) {
        this.aiService = aiService;
        this.prompts = prompts;
        this.assetRepository = assetRepository;
        this.objectMapper = objectMapper;
    }

    public Asset translateToSpanish(Asset english, String englishBody) {
        AiResult result = aiService.generate(prompts.forTranslation(englishBody, Language.ES.displayName));

        Asset es = new Asset();
        es.setCompanyId(english.getCompanyId());
        es.setType(english.getType());
        es.setLanguage(Language.ES);
        // The translation reaches customers too, so it waits for its own approval.
        es.setStatus(AssetStatus.PENDING_APPROVAL);
        es.setTitle(english.getTitle());
        es.setParentAssetId(english.getId());
        es.setVersion(english.getVersion());
        es.setTokensUsed((int) Math.min(Integer.MAX_VALUE, result.totalTokens()));
        es.setContentJson(toContentJson(english.getTitle(), Language.ES, result.text(), result.model()));
        return assetRepository.save(es);
    }

    String toContentJson(String title, Language language, String body, String model) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("title", title);
        content.put("language", language.name());
        content.put("body", body);
        content.put("model", model);
        try {
            return objectMapper.writeValueAsString(content);
        } catch (Exception e) {
            return "{}";
        }
    }
}
