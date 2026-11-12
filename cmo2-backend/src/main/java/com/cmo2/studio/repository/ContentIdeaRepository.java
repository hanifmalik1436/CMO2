package com.cmo2.studio.repository;

import com.cmo2.studio.domain.ContentIdea;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ContentIdeaRepository extends JpaRepository<ContentIdea, Long> {

    List<ContentIdea> findByTenantIdAndSourceIdOrderByPositionAsc(Long tenantId, Long sourceId);

    void deleteByTenantIdAndSourceId(Long tenantId, Long sourceId);
}
