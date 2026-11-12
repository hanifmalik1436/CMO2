package com.cmo2.studio.repository;

import com.cmo2.studio.domain.ContentBatch;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ContentBatchRepository extends JpaRepository<ContentBatch, Long> {

    List<ContentBatch> findByTenantIdAndCompanyIdOrderByCreatedAtDesc(Long tenantId, Long companyId, Pageable page);
}
