package com.acmp.compute.mapper;

import com.acmp.compute.entity.TenantSpecQuota;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

@Mapper
public interface TenantSpecQuotaMapper {
    int insert(TenantSpecQuota quota);
    int updateTotal(@Param("id") String id, @Param("total") int total);
    int updateUsed(@Param("id") String id, @Param("used") int used);
    Optional<TenantSpecQuota> findById(@Param("id") String id);
    Optional<TenantSpecQuota> findByTenantAndSpec(@Param("tenantId") String tenantId,
                                                  @Param("specId") String specId);
    List<TenantSpecQuota> findByTenantId(@Param("tenantId") String tenantId);
    int countBySpecId(@Param("specId") String specId);
    int sumTotalBySpecId(@Param("specId") String specId);
    int sumUsedBySpecId(@Param("specId") String specId);
    int countUsedByTenantId(@Param("tenantId") String tenantId);
    int deleteById(@Param("id") String id);
    int deleteByTenantId(@Param("tenantId") String tenantId);
    int deleteAll();
}
