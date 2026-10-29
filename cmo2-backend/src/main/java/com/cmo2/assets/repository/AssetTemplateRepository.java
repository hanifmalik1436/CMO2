package com.cmo2.assets.repository;

import com.cmo2.assets.domain.AssetTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AssetTemplateRepository extends JpaRepository<AssetTemplate, Long> {

    List<AssetTemplate> findByType(String type);
}
