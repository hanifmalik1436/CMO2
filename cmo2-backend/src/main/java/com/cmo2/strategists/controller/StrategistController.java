package com.cmo2.strategists.controller;

import com.cmo2.strategists.dto.StrategistDtos.AgentResponse;
import com.cmo2.strategists.dto.StrategistDtos.ApproveGateRequest;
import com.cmo2.strategists.dto.StrategistDtos.GateResponse;
import com.cmo2.strategists.dto.StrategistDtos.MessageRequest;
import com.cmo2.strategists.dto.StrategistDtos.MessageResponse;
import com.cmo2.strategists.dto.StrategistDtos.SessionDetailResponse;
import com.cmo2.strategists.dto.StrategistDtos.SessionResponse;
import com.cmo2.strategists.dto.StrategistDtos.StartSessionRequest;
import com.cmo2.strategists.service.AgentRegistry;
import com.cmo2.strategists.service.ReviewGateService;
import com.cmo2.strategists.service.SessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
@Tag(name = "AI Strategists")
public class StrategistController {

    private final AgentRegistry agents;
    private final SessionService sessions;
    private final ReviewGateService gates;

    public StrategistController(AgentRegistry agents, SessionService sessions, ReviewGateService gates) {
        this.agents = agents;
        this.sessions = sessions;
        this.gates = gates;
    }

    @GetMapping("/strategists/agents")
    @Operation(summary = "List the strategist council, grouped by phase")
    public List<AgentResponse> listAgents() {
        return agents.list();
    }

    @PostMapping("/companies/{companyId}/strategists/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Start a strategist session for a company")
    public SessionResponse start(@PathVariable Long companyId, @Valid @RequestBody StartSessionRequest request) {
        return sessions.start(companyId, request.agentCode(), request.avatarPersona());
    }

    @GetMapping("/companies/{companyId}/strategists/sessions")
    @Operation(summary = "List a company's strategist sessions")
    public List<SessionResponse> listSessions(@PathVariable Long companyId) {
        return sessions.list(companyId);
    }

    @GetMapping("/strategists/sessions/{sessionId}")
    @Operation(summary = "Get a session with its conversation history")
    public SessionDetailResponse getSession(@PathVariable Long sessionId) {
        return sessions.get(sessionId);
    }

    @PostMapping("/strategists/sessions/{sessionId}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Send a message to the agent and receive its reply")
    public MessageResponse sendMessage(@PathVariable Long sessionId, @Valid @RequestBody MessageRequest request) {
        return sessions.sendMessage(sessionId, request.content());
    }

    @PostMapping("/strategists/sessions/{sessionId}/deliverables")
    @Operation(summary = "Produce the agent's deliverables for the company")
    public SessionResponse produceDeliverables(@PathVariable Long sessionId) {
        return sessions.produceDeliverables(sessionId);
    }

    @GetMapping("/companies/{companyId}/strategists/gates")
    @Operation(summary = "List the review gates RG-1..RG-4 for a company")
    public List<GateResponse> listGates(@PathVariable Long companyId) {
        return gates.list(companyId);
    }

    @PostMapping("/companies/{companyId}/strategists/gates/{gateCode}/approve")
    @Operation(summary = "Approve a review gate (releases that phase's deliverables)")
    public GateResponse approveGate(@PathVariable Long companyId, @PathVariable String gateCode,
                                    @Valid @RequestBody(required = false) ApproveGateRequest request) {
        return gates.approve(companyId, gateCode, request == null ? null : request.notes());
    }
}
