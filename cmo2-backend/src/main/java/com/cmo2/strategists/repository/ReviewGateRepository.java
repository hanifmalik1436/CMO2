package com.cmo2.strategists.repository;

import com.cmo2.strategists.domain.ReviewGate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReviewGateRepository extends JpaRepository<ReviewGate, Long> {

    List<ReviewGate> findByCompanyIdOrderByGateCodeAsc(Long companyId);

    Optional<ReviewGate> findFirstByCompanyIdAndGateCode(Long companyId, String gateCode);
}
