package com.cmo2.docs.domain;

import com.cmo2.shared.tenancy.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

/** Where one customer's records live in the agency's workspace tools. */
@Entity
@Table(name = "company_doc_targets",
        uniqueConstraints = @UniqueConstraint(name = "uk_doc_targets_company", columnNames = "company_id"))
@Getter
@Setter
public class CompanyDocTargets extends BaseTenantEntity {

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    @Column(name = "notion_page_id", length = 64)
    private String notionPageId;

    @Column(name = "notion_url", length = 512)
    private String notionUrl;

    @Column(name = "confluence_page_id", length = 64)
    private String confluencePageId;

    @Column(name = "confluence_url", length = 512)
    private String confluenceUrl;

    @Column(name = "linear_project_id", length = 64)
    private String linearProjectId;

    @Column(name = "linear_url", length = 512)
    private String linearUrl;

    @Column(name = "last_error", length = 1000)
    private String lastError;
}
