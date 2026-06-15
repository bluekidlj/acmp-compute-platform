package com.acmp.compute.mapper;

import com.acmp.compute.entity.Organization;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

@Mapper
public interface OrganizationMapper {
    Optional<Organization> findById(@Param("id") String id);
    List<Organization> findAll();
    int insert(Organization org);
    int update(Organization org);
}
