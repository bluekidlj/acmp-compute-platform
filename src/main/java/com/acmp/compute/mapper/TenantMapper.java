package com.acmp.compute.mapper;

import com.acmp.compute.entity.Tenant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

@Mapper
public interface TenantMapper {
    int insert(Tenant tenant);
    int update(Tenant tenant);
    Optional<Tenant> findById(@Param("id") String id);
    Optional<Tenant> findByName(@Param("name") String name);
    List<Tenant> findAll();
    int deleteById(@Param("id") String id);
}
