package com.cmo2.studio.repository;

import com.cmo2.studio.domain.ContentIdea;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ContentIdeaRepository extends JpaRepository<ContentIdea, Long> {

    List<ContentIdea> findByTenantIdAndSourceIdOrderByPositionAsc(Long tenantId, Long sourceId);

    void deleteByTenantIdAndSourceId(Long tenantId, Long sourceId);

    long countByTenantIdAndSourceId(Long tenantId, Long sourceId);

    /** [sourceId, idea count] for every source of the customer that has ideas. */
    @Query("select i.sourceId, count(i) from ContentIdea i where i.tenantId = :tenantId and i.companyId = :companyId "
            + "group by i.sourceId")
    List<Object[]> countsBySource(@Param("tenantId") Long tenantId, @Param("companyId") Long companyId);
}
