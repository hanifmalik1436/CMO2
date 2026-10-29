package com.cmo2.strategists.domain;

import com.cmo2.shared.tenancy.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** One turn in a strategist session (conversation history). */
@Entity
@Table(name = "strategist_messages")
@Getter
@Setter
public class StrategistMessage extends BaseTenantEntity {

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private MessageRole role;

    @Lob
    @Column
    private String content;

    @Column(name = "tokens_used", nullable = false)
    private int tokensUsed = 0;
}
