package com.acmp.compute.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 算力规格 = 预设的 K8s ResourceRequirements 模板 + nodeSelector + tolerations。
 * 用户提交任务时只需引用规格名，平台自动翻译为完整 Pod Spec。
 *
 * <h2>完整字段清单</h2>
 * | 字段 | 含义 | 生成的 K8s 键 |
 * |------|------|-------------|
 * | defaultGpuCount | 每副本 GPU 卡数 | limits["nvidia.com/gpu"] 等 |
 * | defaultGpumemMb | 每副本 GPU 显存（HAMi vGPU）| limits["nvidia.com/gpumem"] |
 * | defaultGpucores | 每副本 GPU 算力（HAMi vGPU）| limits["nvidia.com/gpucores"] |
 * | defaultCpuCores | 每副本 CPU 核数 | limits["cpu"] |
 * | defaultMemoryGib | 每副本内存 GiB | limits["memory"] |
 * | nodeSelector | Pod 调度到哪类节点 | Pod.spec.nodeSelector |
 * | tolerations | 容忍节点污点 | Pod.spec.tolerations |
 * | resourceQuotaKey | 平台计量键 | limits["platform.io/{spec}"] = 1 |
 * | memoryGb | 展示用，总内存参考值 | - |
 * | gpuBrand | GPU 品牌（NVIDIA/HYGON/HUAWEI_ASCEND）| 决定使用哪个 GPU 资源键 |
 *
 * <h2>磁盘/存储说明</h2>
 * 磁盘（storage）目前未纳入 ComputeSpec 字段。
 * 平台 Role 权限中包含 PersistentVolumeClaim（PVC）操作，存储配额由 K8s 集群侧
 * ResourceQuota 的 storage limits 或单独 StorageClass 策略控制，不在平台配额体系中。
 *
 * <h2>资源键体系（两套独立的键）</h2>
 *
 * 真实硬件资源键（K8s 调度器真实调度）：
 *  - defaultGpuCount   → limits["nvidia.com/gpu"] / limits["amd.com/dcu"] / limits["huawei.com/ascend910"]
 *  - defaultGpumemMb   → limits["nvidia.com/gpumem"]（HAMi vGPU 显存，仅 NVIDIA）
 *  - defaultGpucores   → limits["nvidia.com/gpucores"]（HAMi vGPU 算力，仅 NVIDIA）
 *  - defaultCpuCores   → limits["cpu"]
 *  - defaultMemoryGib  → limits["memory"]
 *
 * 平台计量键（仅用于 ResourceQuota 计量副本数，不参与 K8s 调度）：
 *  - resourceQuotaKey  → limits["platform.io/{spec}"] = 1（每副本计1，ResourceQuota.used 累加为总副本数）
 *
 * <h2>为什么需要两套键</h2>
 * HAMi vGPU 环境下 nvidia.com/gpu=1 可能只是1个虚拟GPU而非整卡。
 * 平台需要独立的 platform.io/{spec} 计量键来追踪"该 namespace 跑了几个该规格 Pod"，
 * ResourceQuota 据此限制副本数，而不依赖真实 GPU 数量。
 * K8s 调度器只看到 nvidia.com/gpu 等真实资源键。
 *
 * <h2>defaultGpuCount 与 resourceQuotaKey 的关系</h2>
 * defaultGpuCount：控制 Pod 向 K8s 实际申请多少 GPU（决定调度结果）
 * resourceQuotaKey：平台计量用，不影响 K8s 调度，但影响 ResourceQuota 能否允许该 Pod 运行
 * 两者协同工作：Pod 因为 defaultGpuCount 被调度到有 GPU 的节点，又因为 resourceQuotaKey=1
 * 而在 ResourceQuota 的 platform.io/{spec} 计量中占 1 份配额。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ComputeSpec {
    private String id;
    private String name;
    private String displayName;
    private GpuBrand gpuBrand;
    // ── 预设 ResourceRequirements ──
    private Integer defaultGpuCount;
    private Integer defaultGpumemMb;
    private Integer defaultGpucores;
    private Integer defaultCpuCores;
    private Integer defaultMemoryGib;
    // ── 节点调度 ──
    /** JSON: {"pool":"nvidia-gpu"} */
    private String nodeSelector;
    /** JSON: [{"key":"nvidia.com/gpu","operator":"Exists","effect":"NoSchedule"}] */
    private String tolerations;
    /** ResourceQuota 中使用的资源键，默认 platform.io/{name} */
    private String resourceQuotaKey;
    // ── 元信息 ──
    private Integer memoryGb;
    private String description;
    private Instant createdAt;
    private Instant updatedAt;

    /** 获取 ResourceQuota 键，若未设置则生成默认值 */
    public String getResourceQuotaKey() {
        if (resourceQuotaKey != null && !resourceQuotaKey.isEmpty()) return resourceQuotaKey;
        return "platform.io/" + name;
    }
}
