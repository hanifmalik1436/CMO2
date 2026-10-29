package com.cmo2.strangeloop.controller;

import com.cmo2.strangeloop.dto.LoopDtos.ExperimentDecision;
import com.cmo2.strangeloop.dto.LoopDtos.ExperimentRejection;
import com.cmo2.strangeloop.experiments.ExperimentService;
import com.cmo2.strangeloop.experiments.ExperimentService.ExperimentView;
import com.cmo2.strangeloop.learning.LearningService;
import com.cmo2.strangeloop.learning.LearningService.LearningView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
@Tag(name = "Strange Loop learning")
public class ExperimentController {

    private final LearningService learning;
    private final ExperimentService experiments;

    public ExperimentController(LearningService learning, ExperimentService experiments) {
        this.learning = learning;
        this.experiments = experiments;
    }

    @GetMapping("/companies/{companyId}/learning")
    @Operation(summary = "Stage 2's learning records for a customer, newest first")
    public List<LearningView> learning(@PathVariable Long companyId, @RequestParam(defaultValue = "5") int limit) {
        return learning.recent(companyId, limit);
    }

    @GetMapping("/companies/{companyId}/experiments")
    @Operation(summary = "Stage 5's experiments for a customer, newest first")
    public List<ExperimentView> experiments(@PathVariable Long companyId, @RequestParam(defaultValue = "20") int limit) {
        return experiments.list(companyId, limit);
    }

    @PostMapping("/experiments/{id}/approve")
    @Operation(summary = "Approve a proposed experiment (applies a custom value change; marks a suggestion done)")
    public ExperimentView approve(@PathVariable Long id, @Valid @RequestBody(required = false) ExperimentDecision request) {
        return experiments.approve(id, request == null ? null : request.note());
    }

    @PostMapping("/experiments/{id}/reject")
    @Operation(summary = "Turn down a proposed experiment")
    public ExperimentView reject(@PathVariable Long id, @Valid @RequestBody ExperimentRejection request) {
        return experiments.reject(id, request.reason());
    }
}
