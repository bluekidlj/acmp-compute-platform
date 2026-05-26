package com.acmp.compute.mapper;

import com.acmp.compute.entity.TrainingJobRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

@Mapper
public interface TrainingJobRecordMapper {

    int insert(TrainingJobRecord record);

    int update(TrainingJobRecord record);

    Optional<TrainingJobRecord> findById(@Param("id") String id);

    List<TrainingJobRecord> findByWorkspaceId(@Param("workspaceId") String workspaceId);
}
