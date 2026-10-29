package com.cmo2.strangeloop.learning;

import com.cmo2.shared.exceptions.ApiException;
import com.cmo2.shared.tenancy.TenantGuard;
import com.cmo2.tenancy.repository.CompanyRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Stores Stage 2's learning records and shows them to the owner. */
@Service
public class LearningService {

    private static final int MAX = 20;

    private final LearningRecordRepository records;
    private final CompanyRepository companies;
    private final ObjectMapper json;

    public LearningService(LearningRecordRepository records, CompanyRepository companies, ObjectMapper json) {
        this.records = records;
        this.companies = companies;
        this.json = json;
    }

    /** A testable guess: {@code metric} should move {@code expectedDirection} ("up"/"down"). */
    public record Hypothesis(String statement, String metric, String expectedDirection, double confidence) {}

    public record LearningView(Long iterationId, int iterationNo, String source, List<Map<String, Object>> facts,
                               List<String> observations, List<Hypothesis> hypotheses, double confidence, String note,
                               Instant createdAt) {}

    /** Internal (loop threads). Re-running a cycle's Stage 2 after a crash replaces its record. */
    @Transactional
    public void record(Long tenantId, Long companyId, Long iterationId, int iterationNo, String source,
                       List<Map<String, Object>> facts, List<String> observations, List<Hypothesis> hypotheses,
                       double confidence, String note) {
        LearningRecord r = records.findByTenantIdAndIterationId(tenantId, iterationId).orElseGet(LearningRecord::new);
        r.setTenantId(tenantId);
        r.setCompanyId(companyId);
        r.setIterationId(iterationId);
        r.setIterationNo(iterationNo);
        r.setSource(source);
        r.setFactsJson(write(facts));
        r.setObservationsJson(write(observations));
        r.setHypothesesJson(write(hypotheses));
        r.setConfidence(confidence);
        r.setNote(note == null || note.length() <= 1000 ? note : note.substring(0, 1000));
        records.save(r);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','STRATEGIST','ANALYST')")
    public List<LearningView> recent(Long companyId, int limit) {
        var company = TenantGuard.owned(companies.findById(companyId))
                .orElseThrow(() -> ApiException.notFound("Company not found"));
        return records.findByTenantIdAndCompanyIdOrderByCreatedAtDesc(company.getTenantId(), companyId,
                        PageRequest.of(0, Math.min(Math.max(limit, 1), MAX)))
                .stream()
                .map(r -> new LearningView(r.getIterationId(), r.getIterationNo(), r.getSource(),
                        read(r.getFactsJson(), new TypeReference<>() {}, List.of()),
                        read(r.getObservationsJson(), new TypeReference<>() {}, List.of()),
                        read(r.getHypothesesJson(), new TypeReference<>() {}, List.of()),
                        r.getConfidence(), r.getNote(), r.getCreatedAt()))
                .toList();
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }

    private <T> T read(String value, TypeReference<T> type, T empty) {
        try {
            if (value != null && !value.isBlank()) return json.readValue(value, type);
        } catch (Exception ignored) {
            // shown as empty
        }
        return empty;
    }
}
