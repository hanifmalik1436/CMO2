package com.cmo2.ai.controller;

import com.cmo2.ai.AiInteractionService;
import com.cmo2.ai.AiInteractionService.Interaction;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
@Tag(name = "AI usage")
public class AiInteractionController {

    private final AiInteractionService interactions;

    public AiInteractionController(AiInteractionService interactions) {
        this.interactions = interactions;
    }

    @GetMapping("/companies/{companyId}/ai-interactions")
    @Operation(summary = "The customer's latest AI calls with prompts and responses (OWNER/ADMIN)")
    public List<Interaction> recent(@PathVariable Long companyId, @RequestParam(defaultValue = "20") int limit) {
        return interactions.recent(companyId, limit);
    }
}
