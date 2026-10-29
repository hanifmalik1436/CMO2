package com.cmo2.tenancy.controller;

import com.cmo2.tenancy.dto.CompanyDtos.CompanyResponse;
import com.cmo2.tenancy.dto.CompanyDtos.CreateCompanyRequest;
import com.cmo2.tenancy.dto.CompanyDtos.UpdateCompanyRequest;
import com.cmo2.tenancy.service.CompanyService;
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
@RequestMapping("/api/companies")
@Tag(name = "Companies")
public class CompanyController {

    private final CompanyService companyService;

    public CompanyController(CompanyService companyService) {
        this.companyService = companyService;
    }

    @GetMapping
    @Operation(summary = "List the current tenant's companies")
    public List<CompanyResponse> list() {
        return companyService.list();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one company")
    public CompanyResponse get(@PathVariable Long id) {
        return companyService.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a company")
    public CompanyResponse create(@Valid @RequestBody CreateCompanyRequest request) {
        return companyService.create(request);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a company")
    public CompanyResponse update(@PathVariable Long id, @Valid @RequestBody UpdateCompanyRequest request) {
        return companyService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Archive a company")
    public void archive(@PathVariable Long id) {
        companyService.archive(id);
    }
}
