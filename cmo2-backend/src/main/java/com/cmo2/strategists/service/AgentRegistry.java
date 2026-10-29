package com.cmo2.strategists.service;

import com.cmo2.shared.exceptions.ApiException;
import com.cmo2.strategists.domain.StrategistAgent;
import com.cmo2.strategists.dto.StrategistDtos.AgentResponse;
import com.cmo2.strategists.repository.StrategistAgentRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Read access to the seeded strategist agents (global reference data). */
@Service
public class AgentRegistry {

    private final StrategistAgentRepository repository;
    private final ObjectMapper objectMapper;

    public AgentRegistry(StrategistAgentRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<AgentResponse> list() {
        return repository.findAllByOrderByPhaseAscSortOrderAsc().stream()
                .map(a -> new AgentResponse(a.getCode(), a.getName(), a.getPhase(), deliverables(a)))
                .toList();
    }

    @Transactional(readOnly = true)
    public StrategistAgent requireByCode(String code) {
        return repository.findByCode(code)
                .orElseThrow(() -> ApiException.badRequest("Unknown strategist agent: " + code));
    }

    public List<String> deliverables(StrategistAgent agent) {
        if (agent.getDeliverablesJson() == null || agent.getDeliverablesJson().isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(agent.getDeliverablesJson(), new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }
}
