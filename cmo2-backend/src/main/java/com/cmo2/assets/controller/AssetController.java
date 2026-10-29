package com.cmo2.assets.controller;

import com.cmo2.assets.dto.AssetDtos.AssetResponse;
import com.cmo2.assets.dto.AssetDtos.DeployRequest;
import com.cmo2.assets.dto.AssetDtos.DeploymentResponse;
import com.cmo2.assets.dto.AssetDtos.GenerateRequest;
import com.cmo2.assets.dto.AssetDtos.GenerateResponse;
import com.cmo2.assets.dto.AssetDtos.TemplateResponse;
import com.cmo2.assets.repository.AssetTemplateRepository;
import com.cmo2.assets.service.Deployer;
import com.cmo2.assets.service.GenerationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
@Tag(name = "Asset Generator")
public class AssetController {

    private final GenerationService generationService;
    private final Deployer deployer;
    private final AssetTemplateRepository templateRepository;

    public AssetController(GenerationService generationService, Deployer deployer,
                           AssetTemplateRepository templateRepository) {
        this.generationService = generationService;
        this.deployer = deployer;
        this.templateRepository = templateRepository;
    }

    @PostMapping("/companies/{companyId}/assets")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Generate an asset (EN) and auto-translate to ES; tokens metered")
    public GenerateResponse generate(@PathVariable Long companyId, @Valid @RequestBody GenerateRequest request) {
        return generationService.generate(companyId, request.type(), request.brief());
    }

    @GetMapping("/companies/{companyId}/assets")
    @Operation(summary = "List a company's generated assets (newest first)")
    public List<AssetResponse> list(@PathVariable Long companyId) {
        return generationService.list(companyId);
    }

    @GetMapping("/assets/{assetId}")
    @Operation(summary = "Get one asset with its content")
    public AssetResponse get(@PathVariable Long assetId) {
        return generationService.get(assetId);
    }

    @PostMapping("/assets/{assetId}/deploy")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Publish an approved asset (live GHL social posts are scheduled in the Social Planner)")
    public DeploymentResponse deploy(@PathVariable Long assetId, @Valid @RequestBody DeployRequest request) {
        return deployer.deploy(assetId, request);
    }

    @GetMapping("/assets/{assetId}/deployments")
    @Operation(summary = "Deployment history for an asset")
    public List<DeploymentResponse> deployments(@PathVariable Long assetId) {
        return deployer.listForAsset(assetId);
    }

    @GetMapping("/asset-templates")
    @Operation(summary = "List the global asset templates for the type picker")
    public List<TemplateResponse> templates() {
        return templateRepository.findAll().stream().map(TemplateResponse::of).toList();
    }
}
