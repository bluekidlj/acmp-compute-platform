package com.acmp.compute.service;

import com.acmp.compute.dto.PhysicalClusterRegisterRequest;
import com.acmp.compute.dto.PhysicalClusterResponse;
import com.acmp.compute.entity.PhysicalCluster;
import com.acmp.compute.k8s.KubernetesClientManager;
import com.acmp.compute.mapper.PhysicalClusterMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 管理员物理集群服务：注册、验证、管理多个 K8s 集群。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminPhysicalClusterService {

    private final PhysicalClusterMapper physicalClusterMapper;
    private final EncryptionService encryptionService;
    private final KubernetesClientManager clientManager;

    /**
     * 注册新的物理集群。
     * 
     * 流程：
     * 1. 校验 kubeconfig 有效性
     * 2. Base64 解码并验证内容
     * 3. AES 加密后保存到数据库
     * 4. 初始化客户端缓存
     * 
     * @param request 包含集群名称、描述、kubeconfig（Base64编码）
     * @return 集群信息响应
     */
    @Transactional(rollbackFor = Exception.class)
    public PhysicalClusterResponse registerCluster(PhysicalClusterRegisterRequest request) {
        // 1. Base64 解码 kubeconfig
        String kubeconfigPlain;
        try {
            kubeconfigPlain = new String(java.util.Base64.getDecoder().decode(request.getKubeconfigBase64()));
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("kubeconfig Base64 解码失败: " + e.getMessage());
        }
        
        // 2. 验证 kubeconfig 是否可用
        if (!clientManager.validateKubeconfig(kubeconfigPlain)) {
            throw new RuntimeException("kubeconfig 验证失败：无法连接到 K8s 集群");
        }
        
        // 3. AES 加密并保存
        String encryptedKubeconfig = encryptionService.encrypt(kubeconfigPlain);
        
        String id = UUID.randomUUID().toString();
        PhysicalCluster cluster = PhysicalCluster.builder()
                .id(id)
                .name(request.getName())
                .description(request.getDescription())
                .gpuTypes(request.getGpuTypes() != null ? request.getGpuTypes() : "NVIDIA")
                .location(request.getLocation() != null ? request.getLocation() : "default")
                .kubeconfigBase64Encrypted(encryptedKubeconfig)
                .status("active")
                .totalGpuSlots(0)
                .build();
        
        physicalClusterMapper.insert(cluster);
        
        log.info("✓ 已成功注册物理集群 {} (id: {})", request.getName(), id);
        
        return toResponse(cluster);
    }

    private PhysicalClusterResponse toResponse(PhysicalCluster cluster) {
        return PhysicalClusterResponse.builder()
                .id(cluster.getId())
                .name(cluster.getName())
                .description(cluster.getDescription())
                .status(cluster.getStatus())
                .totalGpuSlots(cluster.getTotalGpuSlots())
                .gpuTypes(cluster.getGpuTypes())
                .location(cluster.getLocation())
                .createdAt(cluster.getCreatedAt())
                .build();
    }
}
