package com.cmo2.strategists.dto;

import com.cmo2.strategists.domain.ReviewGate;
import com.cmo2.strategists.domain.StrategistMessage;
import com.cmo2.strategists.domain.StrategistSession;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/** Request/response DTOs for the AI Strategist Council. */
public final class StrategistDtos {

    private StrategistDtos() {}

    public record AgentResponse(String code, String name, int phase, List<String> deliverables) {}

    public record StartSessionRequest(
            @NotBlank String agentCode,
            @Size(max = 4000) String avatarPersona
    ) {}

    public record MessageRequest(@NotBlank @Size(max = 8000) String content) {}

    public record MessageResponse(Long id, String role, String content, int tokensUsed, Instant createdAt) {
        public static MessageResponse of(StrategistMessage m) {
            return new MessageResponse(m.getId(), m.getRole().name(), m.getContent(), m.getTokensUsed(), m.getCreatedAt());
        }
    }

    public record SessionResponse(
            Long id, Long companyId, String agentCode, String agentName,
            String status, String deliverablesJson, Instant createdAt
    ) {
        public static SessionResponse of(StrategistSession s, String agentName) {
            return new SessionResponse(s.getId(), s.getCompanyId(), s.getAgentCode(), agentName,
                    s.getStatus().name(), s.getDeliverablesJson(), s.getCreatedAt());
        }
    }

    public record SessionDetailResponse(SessionResponse session, List<MessageResponse> messages) {}

    public record GateResponse(String gateCode, int phase, String status, Long approvedBy, Instant approvedAt, String notes) {
        public static GateResponse of(ReviewGate g) {
            return new GateResponse(g.getGateCode(), g.getPhase(), g.getStatus().name(),
                    g.getApprovedBy(), g.getApprovedAt(), g.getNotes());
        }
    }

    public record ApproveGateRequest(@Size(max = 1000) String notes) {}
}
