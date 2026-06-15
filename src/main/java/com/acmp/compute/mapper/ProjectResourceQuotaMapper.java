package com.acmp.compute.mapper;

import com.acmp.compute.entity.ProjectResourceQuota;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Mapper
public interface ProjectResourceQuotaMapper {
    int insert(ProjectResourceQuota quota);
    int updateTotalNodes(@Param("id") String id, @Param("totalNodes") int totalNodes);
    int updateUsedNodes(@Param("id") String id, @Param("usedNodes") int usedNodes);
    Optional<ProjectResourceQuota> findById(@Param("id") String id);

    /** 查项目在指定 (pool, spec) 下的配额行 */
    Optional<ProjectResourceQuota> findByProjectPoolSpec(@Param("projectId") String projectId,
                                                        @Param("resourcePoolId") String resourcePoolId,
                                                        @Param("specId") String specId);

    /** 列出项目所有配额（含池/规格名） */
    List<Map<String, Object>> findByProjectId(@Param("projectId") String projectId);
    /** 列出项目所有配额（按池类型过滤） */
    List<Map<String, Object>> findByProjectIdAndPoolType(@Param("projectId") String projectId,
                                                         @Param("poolType") String poolType);

    int deleteById(@Param("id") String id);
    int deleteByProjectId(@Param("projectId") String projectId);
}
