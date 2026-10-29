package com.cmo2.docs.controller;

import com.cmo2.docs.domain.DocProvider;
import com.cmo2.docs.dto.DocsDtos.DocConnectionResponse;
import com.cmo2.docs.dto.DocsDtos.DocTargetsResponse;
import com.cmo2.docs.dto.DocsDtos.SaveDocConnectionRequest;
import com.cmo2.docs.dto.DocsDtos.UpdateDocTargetsRequest;
import com.cmo2.docs.service.DocConnectionService;
import com.cmo2.docs.service.DocTargetService;
import com.cmo2.shared.exceptions.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
@Tag(name = "Workspace tools")
public class DocsController {

    private final DocConnectionService connections;
    private final DocTargetService targets;

    public DocsController(DocConnectionService connections, DocTargetService targets) {
        this.connections = connections;
        this.targets = targets;
    }

    @GetMapping("/docs/connections")
    @Operation(summary = "The agency's Notion / Confluence / Linear / Slack connections (secrets masked)")
    public List<DocConnectionResponse> list() {
        return connections.list();
    }

    @PutMapping("/docs/connections/{provider}")
    @Operation(summary = "Save a tool's settings and verify them against the tool")
    public DocConnectionResponse save(@PathVariable String provider, @Valid @RequestBody SaveDocConnectionRequest request) {
        return connections.save(parse(provider), request);
    }

    @PostMapping("/docs/connections/{provider}/test")
    @Operation(summary = "Re-check a saved tool connection")
    public DocConnectionResponse test(@PathVariable String provider) {
        return connections.test(parse(provider));
    }

    @DeleteMapping("/docs/connections/{provider}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove a tool connection")
    public void delete(@PathVariable String provider) {
        connections.delete(parse(provider));
    }

    @GetMapping("/docs/targets")
    @Operation(summary = "Every customer's Notion page, Confluence page and Linear project")
    public List<DocTargetsResponse> targets() {
        return targets.list();
    }

    @PostMapping("/companies/{companyId}/doc-targets/provision")
    @Operation(summary = "Create whatever this customer is missing in the connected tools")
    public DocTargetsResponse provision(@PathVariable Long companyId) {
        return targets.provisionForCompany(companyId);
    }

    @PutMapping("/companies/{companyId}/doc-targets")
    @Operation(summary = "Point a customer at existing pages/projects")
    public DocTargetsResponse update(@PathVariable Long companyId, @Valid @RequestBody UpdateDocTargetsRequest request) {
        return targets.update(companyId, request);
    }

    private static DocProvider parse(String raw) {
        try {
            return DocProvider.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Unknown workspace tool: " + raw);
        }
    }
}
