package com.cmo2.strategists.domain;

public enum SessionStatus {
    ACTIVE,
    COMPLETED,   // deliverables produced, awaiting gate approval
    APPROVED,    // gate approved — deliverables are in the company workspace
    ARCHIVED
}
