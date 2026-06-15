package com.acmp.compute.mapper;

import com.acmp.compute.entity.ModelDeployment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

@Mapper
public interface ModelDeploymentMapper {
    int insert(ModelDeployment entity);
    int update(ModelDeployment entity);
    int updateStatus(@Param("id") String id,
                     @Param("status") String status,
                     @Param("serviceUrl") String serviceUrl);
    int updateActualClusterId(@Param("id") String id, @Param("actualClusterId") String actualClusterId);
    Optional<ModelDeployment> findById(@Param("id") String id);
    List<ModelDeployment> findByProjectId(@Param("projectId") String projectId);
    List<ModelDeployment> findByWorkspaceId(@Param("workspaceId") String workspaceId);
    int deleteById(@Param("id") String id);
    List<ModelDeployment> findAll();
}
