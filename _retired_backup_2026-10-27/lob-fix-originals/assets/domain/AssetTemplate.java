package com.cmo2.assets.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Global template reference data for the type picker (not tenant-owned). */
@Entity
@Table(name = "asset_templates")
@Getter
@Setter
public class AssetTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String type;

    @Column(nullable = false, length = 8)
    private String language;

    @Column(nullable = false, length = 160)
    private String name;

    @Lob
    @Column(name = "body_template")
    private String bodyTemplate;

    @Lob
    @Column(name = "variables_json")
    private String variablesJson;
}
