package com.acmp.compute.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 异构算力：池里的卡。
 *
 * <p>1 张物理卡 + 1 spec → N 节点（slots = cardMem / specGpumem）
 *
 * <p>异构 = 池里多品牌卡共存，每张卡独立选 spec。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PoolCard {
    private String id;
    private String poolId;
    private String gpuBrand;
    private String gpuModel;
    private String nodeName;
    private String serialNo;
    private String specId;
    private Integer slots;
    private String status;
    private Instant createdAt;
    private Instant updatedAt;
}
