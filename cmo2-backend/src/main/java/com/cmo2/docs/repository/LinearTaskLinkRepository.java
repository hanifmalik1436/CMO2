package com.cmo2.docs.repository;

import com.cmo2.docs.domain.LinearTaskLink;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** Lookups name the tenant explicitly: tasks are synced from loop threads. */
public interface LinearTaskLinkRepository extends JpaRepository<LinearTaskLink, Long> {

    List<LinearTaskLink> findByTenantIdAndCompanyId(Long tenantId, Long companyId);
}
