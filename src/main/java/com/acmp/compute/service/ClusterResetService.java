package com.acmp.compute.service;

import com.acmp.compute.dto.ClusterResetResponse;
import com.acmp.compute.entity.PhysicalCluster;
import com.acmp.compute.exception.BadRequestException;
import com.acmp.compute.k8s.KubernetesClientManager;
import com.acmp.compute.mapper.ClusterNodeMapper;
import com.acmp.compute.mapper.ComputeSpecMapper;
import com.acmp.compute.mapper.GpuDeviceMapper;
import com.acmp.compute.mapper.ModelDeploymentMapper;
import com.acmp.compute.mapper.PhysicalClusterMapper;
import com.acmp.compute.mapper.TenantSpecQuotaMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 单管理员 Demo 使用的全部集群调试重置。
 *
 * <p>不实现数据库与 Kubernetes 的事务补偿。每个阶段均打印日志，失败集群返回明确结果，
 * 其他集群继续处理。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClusterResetService {

    private final ModelDeploymentMapper deploymentMapper;
    private final TenantSpecQuotaMapper quotaMapper;
    private final ComputeSpecMapper specMapper;
    private final PhysicalClusterMapper clusterMapper;
    private final ClusterNodeMapper nodeMapper;
    private final GpuDeviceMapper gpuMapper;
    private final KubernetesClientManager clientManager;
    private final ClusterInventoryService inventoryService;

    public ClusterResetResponse resetAll() {
        if (!deploymentMapper.findAll().isEmpty()) {
            throw new BadRequestException("请先删除全部推理服务，再执行集群重置");
        }

        List<PhysicalCluster> clusters = clusterMapper.findAll();
        log.warn("全部集群调试重置开始: clusterCount={}", clusters.size());

        int clearedQuotas = quotaMapper.deleteAll();
        int clearedSpecs = specMapper.deleteAll();
        List<ClusterResetResponse.ClusterResult> results = new ArrayList<>();

        for (PhysicalCluster cluster : clusters) {
            results.add(resetCluster(cluster));
        }

        boolean success = results.stream().allMatch(ClusterResetResponse.ClusterResult::getSuccess);
        log.warn("全部集群调试重置完成: success={}, clearedQuotas={}, clearedSpecs={}",
                success, clearedQuotas, clearedSpecs);
        return ClusterResetResponse.builder()
                .success(success)
                .clearedQuotaCount(clearedQuotas)
                .clearedSpecCount(clearedSpecs)
                .clusters(results)
                .build();
    }

    private ClusterResetResponse.ClusterResult resetCluster(PhysicalCluster cluster) {
        int clearedLabels = 0;
        String labelError = null;
        try {
            log.warn("集群重置阶段: clusterId={}, name={}, stage=remove-node-labels",
                    cluster.getId(), cluster.getName());
            clearedLabels = clientManager.removeAcmpNodeLabels(cluster.getId());
        } catch (Exception exception) {
            labelError = exception.getMessage();
            log.error("集群 Node 标签清理失败，继续重建库存: clusterId={}, error={}",
                    cluster.getId(), labelError);
        }

        try {
            log.warn("集群重置阶段: clusterId={}, name={}, stage=clear-inventory",
                    cluster.getId(), cluster.getName());
            gpuMapper.deleteByClusterId(cluster.getId());
            nodeMapper.deleteByClusterId(cluster.getId());
            clusterMapper.resetInventory(cluster.getId());

            log.warn("集群重置阶段: clusterId={}, name={}, stage=resync",
                    cluster.getId(), cluster.getName());
            inventoryService.sync(cluster.getId());
            boolean success = labelError == null;
            return ClusterResetResponse.ClusterResult.builder()
                    .clusterId(cluster.getId())
                    .clusterName(cluster.getName())
                    .success(success)
                    .clearedNodeLabelCount(clearedLabels)
                    .message(success ? "标签已清理并重新同步完成"
                            : "库存已同步，但标签清理失败: " + labelError)
                    .build();
        } catch (Exception exception) {
            log.error("集群重新同步失败: clusterId={}, error={}",
                    cluster.getId(), exception.getMessage(), exception);
            return ClusterResetResponse.ClusterResult.builder()
                    .clusterId(cluster.getId())
                    .clusterName(cluster.getName())
                    .success(false)
                    .clearedNodeLabelCount(clearedLabels)
                    .message("重新同步失败: " + exception.getMessage())
                    .build();
        }
    }
}
