package com.cmo2.reports.repository;

import com.cmo2.reports.domain.Report;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Reads are tenant-scoped automatically by the Hibernate filter. The metadata listing
 * deliberately excludes {@code content_base64} (the heavy PDF payload) via a projection.
 */
public interface ReportRepository extends JpaRepository<Report, Long> {

    List<Report> findByOrderByCreatedAtDesc(Pageable pageable);
}
