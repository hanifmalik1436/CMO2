package com.cmo2.studio.repository;

import com.cmo2.studio.domain.ContentSource;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ContentSourceRepository extends JpaRepository<ContentSource, Long> {

    List<ContentSource> findByTenantIdAndCompanyIdOrderByCreatedAtDesc(Long tenantId, Long companyId);
}
