package com.cmo2.assets.repository;

import com.cmo2.assets.domain.AssetDeployment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface AssetDeploymentRepository extends JpaRepository<AssetDeployment, Long> {

    List<AssetDeployment> findByAssetIdOrderByCreatedAtDesc(Long assetId);

    List<AssetDeployment> findByTenantIdAndAssetIdInAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAsc(
            Long tenantId, Collection<Long> assetIds, Instant from, Instant to);
}
