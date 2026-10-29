package com.cmo2.strategists.repository;

import com.cmo2.strategists.domain.StrategistMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StrategistMessageRepository extends JpaRepository<StrategistMessage, Long> {

    List<StrategistMessage> findBySessionIdOrderByCreatedAtAsc(Long sessionId);
}
