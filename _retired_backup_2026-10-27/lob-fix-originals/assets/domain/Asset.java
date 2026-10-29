package com.cmo2.assets.domain;

import com.cmo2.shared.tenancy.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * A generated content asset — versioned and bilingual. The Spanish version is a
 * separate row linked to its English source via {@code parentAssetId}; regenerations
 * bump {@code version} and chain through the same field.
 */
@Entity
@Table(name = "assets")
@Getter
@Setter
public class Asset extends BaseTenantEntity {

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AssetType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private Language language = Language.EN;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private AssetStatus status = AssetStatus.GENERATED;

    @Column(length = 255)
    private String title;

    @Lob
    @Column(name = "content_json")
    private String contentJson;

    @Column(nullable = false)
    private int version = 1;

    @Column(name = "parent_asset_id")
    private Long parentAssetId;

    @Column(name = "tokens_used", nullable = false)
    private int tokensUsed = 0;
}
