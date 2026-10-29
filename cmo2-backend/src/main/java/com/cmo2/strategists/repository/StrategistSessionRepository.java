package com.cmo2.strategists.repository;

import com.cmo2.strategists.domain.SessionStatus;
import com.cmo2.strategists.domain.StrategistSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StrategistSessionRepository extends JpaRepository<StrategistSession, Long> {

    List<StrategistSession> findByCompanyIdOrderByCreatedAtDesc(Long companyId);

    List<StrategistSession> findByCompanyIdAndStatus(Long companyId, SessionStatus status);

    Optional<StrategistSession> findFirstByCompanyIdAndStatusOrderByUpdatedAtDesc(Long companyId, SessionStatus status);
}
