package com.cmo2.strangeloop.learning;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** Lookups name the tenant explicitly: learning records are written on loop threads. */
public interface LearningRecordRepository extends JpaRepository<LearningRecord, Long> {

    Optional<LearningRecord> findByTenantIdAndIterationId(Long tenantId, Long iterationId);

    List<LearningRecord> findByTenantIdAndCompanyIdOrderByCreatedAtDesc(Long tenantId, Long companyId, Pageable page);
}
