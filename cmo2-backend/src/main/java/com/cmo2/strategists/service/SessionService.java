package com.cmo2.strategists.service;

import com.cmo2.ai.AiCallScope;
import com.cmo2.ai.AiMessages.AiRequest;
import com.cmo2.ai.AiMessages.AiResult;
import com.cmo2.ai.AiModelTier;
import com.cmo2.ai.AiService;
import com.cmo2.customers.service.CustomerIdentityService;
import com.cmo2.shared.audit.AuditService;
import com.cmo2.shared.exceptions.ApiException;
import com.cmo2.shared.tenancy.TenantGuard;
import com.cmo2.strategists.domain.MessageRole;
import com.cmo2.strategists.domain.SessionStatus;
import com.cmo2.strategists.domain.StrategistAgent;
import com.cmo2.strategists.domain.StrategistMessage;
import com.cmo2.strategists.domain.StrategistSession;
import com.cmo2.strategists.dto.StrategistDtos.MessageResponse;
import com.cmo2.strategists.dto.StrategistDtos.SessionDetailResponse;
import com.cmo2.strategists.dto.StrategistDtos.SessionResponse;
import com.cmo2.strategists.repository.StrategistMessageRepository;
import com.cmo2.strategists.repository.StrategistSessionRepository;
import com.cmo2.tenancy.domain.Company;
import com.cmo2.tenancy.repository.CompanyRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The strategist session engine: assemble company context, hold a conversation with
 * the agent (each turn metered through {@link AiService}), and produce the agent's
 * structured deliverables. All AI calls go through the budgeted AiService.
 */
@Service
public class SessionService {

    private final StrategistSessionRepository sessionRepo;
    private final StrategistMessageRepository messageRepo;
    private final CompanyRepository companyRepo;
    private final AgentRegistry agents;
    private final AiService aiService;
    private final AuditService audit;
    private final ObjectMapper objectMapper;
    private final CustomerIdentityService identity;

    public SessionService(StrategistSessionRepository sessionRepo, StrategistMessageRepository messageRepo,
                          CompanyRepository companyRepo, AgentRegistry agents, AiService aiService,
                          AuditService audit, ObjectMapper objectMapper, CustomerIdentityService identity) {
        this.sessionRepo = sessionRepo;
        this.messageRepo = messageRepo;
        this.companyRepo = companyRepo;
        this.agents = agents;
        this.aiService = aiService;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.identity = identity;
    }

    @Transactional
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','STRATEGIST')")
    public SessionResponse start(Long companyId, String agentCode, String avatarPersona) {
        Company company = TenantGuard.owned(companyRepo.findById(companyId))
                .orElseThrow(() -> ApiException.notFound("Company not found"));
        StrategistAgent agent = agents.requireByCode(agentCode);

        StrategistSession session = new StrategistSession();
        session.setCompanyId(companyId);
        session.setAgentCode(agentCode);
        session.setStatus(SessionStatus.ACTIVE);
        session.setContextJson(buildContext(company, avatarPersona));
        session = sessionRepo.save(session);

        saveMessage(session.getId(), MessageRole.SYSTEM, agent.getSystemPrompt(), 0);
        audit.record("STRATEGIST_SESSION_STARTED", "StrategistSession",
                String.valueOf(session.getId()), agent.getName() + " for company " + companyId);
        return SessionResponse.of(session, agent.getName());
    }

    @Transactional
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','STRATEGIST')")
    public MessageResponse sendMessage(Long sessionId, String content) {
        StrategistSession session = ownedSession(sessionId);
        StrategistAgent agent = agents.requireByCode(session.getAgentCode());

        saveMessage(sessionId, MessageRole.USER, content, 0);

        AiResult result;
        try (AiCallScope scope = AiCallScope.forCompany(session.getCompanyId())) {
            result = aiService.generate(new AiRequest(
                    systemFor(agent, session),
                    content,
                    AiModelTier.STRATEGY,
                    "STRAT:" + agent.getCode(),
                    4096));
        }

        StrategistMessage assistant = saveMessage(sessionId, MessageRole.ASSISTANT, result.text(),
                (int) Math.min(Integer.MAX_VALUE, result.totalTokens()));
        return MessageResponse.of(assistant);
    }

    @Transactional
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','STRATEGIST')")
    public SessionResponse produceDeliverables(Long sessionId) {
        StrategistSession session = ownedSession(sessionId);
        StrategistAgent agent = agents.requireByCode(session.getAgentCode());

        String ask = "Produce your full set of deliverables for this company. "
                + "Deliverables expected: " + agent.getDeliverablesJson();
        AiResult result;
        try (AiCallScope scope = AiCallScope.forCompany(session.getCompanyId())) {
            result = aiService.generate(new AiRequest(
                    systemFor(agent, session), ask, AiModelTier.STRATEGY, "STRAT:" + agent.getCode(), 4096));
        }

        session.setDeliverablesJson(result.text());
        session.setStatus(SessionStatus.COMPLETED);
        sessionRepo.save(session);

        saveMessage(sessionId, MessageRole.ASSISTANT, result.text(),
                (int) Math.min(Integer.MAX_VALUE, result.totalTokens()));
        audit.record("STRATEGIST_DELIVERABLES", "StrategistSession", String.valueOf(sessionId), agent.getName());
        return SessionResponse.of(session, agent.getName());
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','STRATEGIST','ANALYST','VIEWER')")
    public List<SessionResponse> list(Long companyId) {
        return sessionRepo.findByCompanyIdOrderByCreatedAtDesc(companyId).stream()
                .map(s -> SessionResponse.of(s, agentName(s.getAgentCode())))
                .toList();
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','STRATEGIST','ANALYST','VIEWER')")
    public SessionDetailResponse get(Long sessionId) {
        StrategistSession session = ownedSession(sessionId);
        List<MessageResponse> messages = messageRepo.findBySessionIdOrderByCreatedAtAsc(sessionId).stream()
                .map(MessageResponse::of)
                .toList();
        return new SessionDetailResponse(SessionResponse.of(session, agentName(session.getAgentCode())), messages);
    }

    // ---- helpers -------------------------------------------------------------

    private StrategistSession ownedSession(Long sessionId) {
        return TenantGuard.owned(sessionRepo.findById(sessionId))
                .orElseThrow(() -> ApiException.notFound("Session not found"));
    }

    private StrategistMessage saveMessage(Long sessionId, MessageRole role, String content, int tokens) {
        StrategistMessage m = new StrategistMessage();
        m.setSessionId(sessionId);
        m.setRole(role);
        m.setContent(content);
        m.setTokensUsed(tokens);
        return messageRepo.save(m);
    }

    /** The agent's prompt, the session's saved context, and the customer's identity block as it is now. */
    private String systemFor(StrategistAgent agent, StrategistSession session) {
        String block = identity.identityBlock(session.getTenantId(), session.getCompanyId());
        return (agent.getSystemPrompt() == null ? "" : agent.getSystemPrompt())
                + "\n\nCompany context:\n" + (session.getContextJson() == null ? "{}" : session.getContextJson())
                + (block.isBlank() ? "" : "\n\n" + block);
    }

    private String buildContext(Company company, String avatarPersona) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        Map<String, Object> co = new LinkedHashMap<>();
        co.put("name", company.getName());
        co.put("industry", company.getIndustry());
        co.put("brandVoice", company.getBrandVoice());
        co.put("website", company.getWebsite());
        ctx.put("company", co);
        if (avatarPersona != null && !avatarPersona.isBlank()) {
            ctx.put("avatarPersona", avatarPersona.trim());
        }
        try {
            return objectMapper.writeValueAsString(ctx);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String agentName(String code) {
        try {
            return agents.requireByCode(code).getName();
        } catch (Exception e) {
            return code;
        }
    }
}
