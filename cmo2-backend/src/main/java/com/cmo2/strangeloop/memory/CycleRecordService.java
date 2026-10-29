package com.cmo2.strangeloop.memory;

import com.cmo2.shared.exceptions.ApiException;
import com.cmo2.shared.tenancy.TenantGuard;
import com.cmo2.strangeloop.memory.CycleMemoryService.Note;
import com.cmo2.strangeloop.memory.CycleMemoryService.Task;
import com.cmo2.tenancy.repository.CompanyRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The owner's view of a customer's cycle records, newest first. */
@Service
public class CycleRecordService {

    private static final int MAX = 20;

    private final CycleContextRepository records;
    private final CompanyRepository companies;
    private final ObjectMapper json;

    public CycleRecordService(CycleContextRepository records, CompanyRepository companies, ObjectMapper json) {
        this.records = records;
        this.companies = companies;
        this.json = json;
    }

    /** {@code links}: tool → page URL of this cycle's written record. */
    public record CycleRecord(Long iterationId, int iterationNo, Instant periodStart, Instant periodEnd, String summary,
                              List<String> decisions, List<String> tasksCompleted, List<Task> tasksPending,
                              List<String> contentProduced, List<Note> operatorNotes, Map<String, String> links) {}

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','STRATEGIST','ANALYST')")
    public List<CycleRecord> recent(Long companyId, int limit) {
        var company = TenantGuard.owned(companies.findById(companyId))
                .orElseThrow(() -> ApiException.notFound("Company not found"));
        return records.findByTenantIdAndCompanyIdOrderByPeriodEndDesc(company.getTenantId(), companyId,
                        PageRequest.of(0, Math.min(Math.max(limit, 1), MAX)))
                .stream().map(this::toRecord).toList();
    }

    private CycleRecord toRecord(CycleContext c) {
        Map<String, String> links = new LinkedHashMap<>();
        Map<String, Map<String, String>> refs = read(c.getPublishedRefsJson(), new TypeReference<>() {}, Map.of());
        refs.forEach((tool, ref) -> {
            if (ref != null && ref.get("url") != null) links.put(tool, ref.get("url"));
        });
        return new CycleRecord(c.getIterationId(), c.getIterationNo(), c.getPeriodStart(), c.getPeriodEnd(), c.getSummary(),
                read(c.getDecisionsJson(), new TypeReference<>() {}, List.of()),
                read(c.getTasksCompletedJson(), new TypeReference<>() {}, List.of()),
                read(c.getTasksPendingJson(), new TypeReference<>() {}, List.of()),
                read(c.getContentProducedJson(), new TypeReference<>() {}, List.of()),
                read(c.getOperatorNotesJson(), new TypeReference<>() {}, List.of()),
                links);
    }

    private <T> T read(String value, TypeReference<T> type, T empty) {
        try {
            if (value != null && !value.isBlank()) return json.readValue(value, type);
        } catch (Exception ignored) {
            // an unreadable field shows as empty
        }
        return empty;
    }
}
