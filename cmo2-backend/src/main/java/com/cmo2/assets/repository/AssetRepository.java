package com.cmo2.assets.repository;

import com.cmo2.assets.domain.Asset;
import com.cmo2.assets.domain.AssetStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

import java.util.List;

public interface AssetRepository extends JpaRepository<Asset, Long> {

    List<Asset> findByCompanyIdOrderByCreatedAtDesc(Long companyId);

    List<Asset> findByParentAssetId(Long parentAssetId);

    List<Asset> findByTenantIdAndStatusOrderByCreatedAtAsc(Long tenantId, AssetStatus status);

    List<Asset> findByTenantIdAndCompanyIdAndStatusOrderByCreatedAtAsc(Long tenantId, Long companyId, AssetStatus status);

    long countByTenantIdAndCompanyIdAndStatus(Long tenantId, Long companyId, AssetStatus status);

    List<Asset> findByTenantIdAndCompanyIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAsc(
            Long tenantId, Long companyId, Instant from, Instant to);

    List<Asset> findByTenantIdAndCompanyIdAndReviewedAtGreaterThanEqualAndReviewedAtLessThanOrderByReviewedAtAsc(
            Long tenantId, Long companyId, Instant from, Instant to);

    @Query("select a.id from Asset a where a.tenantId = :tenantId and a.companyId = :companyId")
    List<Long> idsFor(@Param("tenantId") Long tenantId, @Param("companyId") Long companyId);
}
