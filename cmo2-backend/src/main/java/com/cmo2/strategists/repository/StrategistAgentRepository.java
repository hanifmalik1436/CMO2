package com.cmo2.strategists.repository;

import com.cmo2.strategists.domain.StrategistAgent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StrategistAgentRepository extends JpaRepository<StrategistAgent, Long> {

    List<StrategistAgent> findAllByOrderByPhaseAscSortOrderAsc();

    Optional<StrategistAgent> findByCode(String code);
}
