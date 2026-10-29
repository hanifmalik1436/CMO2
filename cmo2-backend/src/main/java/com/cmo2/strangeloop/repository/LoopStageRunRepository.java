package com.cmo2.strangeloop.repository;

import com.cmo2.strangeloop.domain.LoopStageRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LoopStageRunRepository extends JpaRepository<LoopStageRun, Long> {

    List<LoopStageRun> findByIterationIdOrderByStageNo(Long iterationId);

    Optional<LoopStageRun> findByIterationIdAndStageNo(Long iterationId, int stageNo);
}
