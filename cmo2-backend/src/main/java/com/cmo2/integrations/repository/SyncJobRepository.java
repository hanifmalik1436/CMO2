package com.cmo2.integrations.repository;

import com.cmo2.integrations.domain.SyncJob;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SyncJobRepository extends JpaRepository<SyncJob, Long> {

    List<SyncJob> findByConnectionIdOrderByStartedAtDesc(Long connectionId, Pageable pageable);
}
