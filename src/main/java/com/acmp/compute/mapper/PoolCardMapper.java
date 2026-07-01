package com.acmp.compute.mapper;

import com.acmp.compute.entity.PoolCard;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface PoolCardMapper {
    int insert(PoolCard card);
    PoolCard findById(@Param("id") String id);
    List<PoolCard> findByPoolId(@Param("poolId") String poolId);
    int exists(@Param("poolId") String poolId,
               @Param("nodeName") String nodeName,
               @Param("serialNo") String serialNo,
               @Param("specId") String specId);
    int deleteById(@Param("id") String id);
    int deleteByPoolId(@Param("poolId") String poolId);

    int sumActiveSlotsByPool(@Param("poolId") String poolId);
    int sumActiveSlotsByPoolAndSpec(@Param("poolId") String poolId, @Param("specId") String specId);

    List<String> findNodeNamesByPoolAndSpec(@Param("poolId") String poolId, @Param("specId") String specId);
}
