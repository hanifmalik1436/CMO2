package com.cmo2.strategists.service;

import com.cmo2.strategists.domain.SessionStatus;
import com.cmo2.strategists.repository.StrategistSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class StrategistDeliverableQuery implements StrategistDeliverableProvider {

    private final StrategistSessionRepository sessionRepo;

    public StrategistDeliverableQuery(StrategistSessionRepository sessionRepo) {
        this.sessionRepo = sessionRepo;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> latestApprovedDeliverables(Long companyId) {
        return sessionRepo.findFirstByCompanyIdAndStatusOrderByUpdatedAtDesc(companyId, SessionStatus.APPROVED)
                .map(com.cmo2.strategists.domain.StrategistSession::getDeliverablesJson);
    }
}
