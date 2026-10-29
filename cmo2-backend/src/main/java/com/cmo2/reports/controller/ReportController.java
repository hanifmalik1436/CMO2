package com.cmo2.reports.controller;

import com.cmo2.reports.dto.ReportDtos.ReportSummary;
import com.cmo2.reports.service.ReportService;
import com.cmo2.reports.service.ReportService.Download;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
@Tag(name = "Reports")
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    @PostMapping("/companies/{companyId}/reports/generate")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Generate an on-demand PDF report for a company")
    public ReportSummary generateForCompany(@PathVariable Long companyId) {
        return reportService.generateForCompany(companyId);
    }

    @PostMapping("/reports/portfolio/generate")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Generate an on-demand portfolio PDF report across all companies")
    public ReportSummary generatePortfolio() {
        return reportService.generatePortfolio();
    }

    @GetMapping("/reports")
    @Operation(summary = "List generated reports, newest first (metadata only)")
    public List<ReportSummary> list(@RequestParam(defaultValue = "50") int limit) {
        return reportService.list(limit);
    }

    @GetMapping("/reports/{reportId}/download")
    @Operation(summary = "Download a report's PDF")
    public ResponseEntity<Resource> download(@PathVariable Long reportId) {
        Download dl = reportService.download(reportId);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + dl.filename() + "\"")
                .body(new ByteArrayResource(dl.content()));
    }
}
