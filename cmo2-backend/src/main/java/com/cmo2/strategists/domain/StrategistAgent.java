package com.cmo2.strategists.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Global reference data for a strategist agent (seeded in V5; editable in-DB). */
@Entity
@Table(name = "strategist_agents")
@Getter
@Setter
public class StrategistAgent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 48, unique = true)
    private String code;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false)
    private int phase;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Lob
    @Column(columnDefinition = "LONGTEXT", name = "deliverables_json")
    private String deliverablesJson;

    @Lob
    @Column(columnDefinition = "LONGTEXT", name = "system_prompt")
    private String systemPrompt;
}
