package com.cmo2.studio.repository;

import com.cmo2.studio.domain.ContentBatch;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ContentBatchRepository extends JpaRepository<ContentBatch, Long> {

    List<ContentBatch> findByTenantIdAndCompanyIdOrderByCreatedAtDesc(Long tenantId, Long companyId, Pageable page);

    /** [sourceId, drafts written] for every source of the customer that has had a batch. */
    @Query("select b.sourceId, coalesce(sum(b.completed), 0) from ContentBatch b "
            + "where b.tenantId = :tenantId and b.companyId = :companyId group by b.sourceId")
    List<Object[]> generatedBySource(@Param("tenantId") Long tenantId, @Param("companyId") Long companyId);

    @Query("select coalesce(sum(b.completed), 0) from ContentBatch b where b.tenantId = :tenantId and b.sourceId = :sourceId")
    long generatedForSource(@Param("tenantId") Long tenantId, @Param("sourceId") Long sourceId);
}
