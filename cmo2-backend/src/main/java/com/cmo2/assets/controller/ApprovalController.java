package com.cmo2.assets.controller;

import com.cmo2.assets.dto.AssetDtos.ApprovalItem;
import com.cmo2.assets.dto.AssetDtos.ApproveRequest;
import com.cmo2.assets.dto.AssetDtos.AssetResponse;
import com.cmo2.assets.dto.AssetDtos.EditContentRequest;
import com.cmo2.assets.dto.AssetDtos.RejectRequest;
import com.cmo2.assets.dto.AssetDtos.SocialAccountsResponse;
import com.cmo2.assets.service.ApprovalService;
import com.cmo2.assets.service.Deployer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
@Tag(name = "Approvals")
public class ApprovalController {

    private final ApprovalService approvals;
    private final Deployer deployer;

    public ApprovalController(ApprovalService approvals, Deployer deployer) {
        this.approvals = approvals;
        this.deployer = deployer;
    }

    @GetMapping("/approvals")
    @Operation(summary = "Drafts by state (default: waiting for approval), across customers or for one")
    public List<ApprovalItem> queue(@RequestParam(required = false) Long companyId,
                                    @RequestParam(required = false) String status) {
        return approvals.queue(companyId, status);
    }

    @PutMapping("/assets/{assetId}/content")
    @Operation(summary = "Edit a draft's text before approving it")
    public AssetResponse edit(@PathVariable Long assetId, @Valid @RequestBody EditContentRequest request) {
        return approvals.editContent(assetId, request.body());
    }

    @PostMapping("/assets/{assetId}/approve")
    @Operation(summary = "Approve a draft so it can be published")
    public AssetResponse approve(@PathVariable Long assetId, @Valid @RequestBody(required = false) ApproveRequest request) {
        return approvals.approve(assetId, request == null ? null : request.note());
    }

    @PostMapping("/assets/{assetId}/reject")
    @Operation(summary = "Reject a draft; it never leaves CMO²")
    public AssetResponse reject(@PathVariable Long assetId, @Valid @RequestBody RejectRequest request) {
        return approvals.reject(assetId, request.reason());
    }

    @GetMapping("/companies/{companyId}/social-accounts")
    @Operation(summary = "The social accounts connected in the customer's GoHighLevel")
    public SocialAccountsResponse socialAccounts(@PathVariable Long companyId) {
        return deployer.socialAccounts(companyId);
    }
}
