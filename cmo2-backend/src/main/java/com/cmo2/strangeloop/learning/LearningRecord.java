package com.cmo2.strangeloop.learning;

import com.cmo2.shared.tenancy.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Stage 2's reading of one cycle: the Java-computed facts and what was concluded from them. */
@Entity
@Table(name = "learning_records")
@Getter
@Setter
public class LearningRecord extends BaseTenantEntity {

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    @Column(name = "iteration_id", nullable = false, unique = true)
    private Long iterationId;

    @Column(name = "iteration_no", nullable = false)
    private int iterationNo;

    /** ai, rules or no-data. */
    @Column(nullable = false, length = 24)
    private String source;

    @Column(name = "facts_json", columnDefinition = "LONGTEXT")
    private String factsJson;

    @Column(name = "observations_json", columnDefinition = "LONGTEXT")
    private String observationsJson;

    @Column(name = "hypotheses_json", columnDefinition = "LONGTEXT")
    private String hypothesesJson;

    @Column(nullable = false)
    private double confidence;

    @Column(length = 1000)
    private String note;
}
