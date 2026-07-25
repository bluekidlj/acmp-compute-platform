package com.acmp.compute.service;

import com.acmp.compute.dto.PhysicalClusterResponse;
import com.acmp.compute.entity.PhysicalCluster;
import com.acmp.compute.exception.ResourceNotFoundException;
import com.acmp.compute.k8s.KubernetesClientManager;
import com.acmp.compute.mapper.PhysicalClusterMapper;
import com.acmp.compute.security.EncryptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PhysicalClusterService {

    private final PhysicalClusterMapper physicalClusterMapper;
    private final EncryptionService encryptionService;
    private final KubernetesClientManager clientManager;
    private final ClusterInventoryService clusterInventoryService;

    @Transactional(rollbackFor = Exception.class)
    public PhysicalClusterResponse register(String name, String description, String kubeconfig) {
        String plainKubeconfig = decodeIfBase64(kubeconfig);
        if (!clientManager.validateKubeconfig(plainKubeconfig)) {
            throw new IllegalArgumentException("kubeconfig 校验失败，无法连接集群");
        }
        String encrypted = encryptionService.encrypt(plainKubeconfig);
        String id = UUID.randomUUID().toString();
        PhysicalCluster cluster = PhysicalCluster.builder()
                .id(id)
                .name(name)
                .description(description)
                .kubeconfigBase64Encrypted(encrypted)
                .status("CONNECTING")
                .build();
        physicalClusterMapper.insert(cluster);
        try {
            clusterInventoryService.sync(id);
        } catch (RuntimeException e) {
            clientManager.closeClient(id);
            physicalClusterMapper.deleteById(id);
            throw e;
        }
        log.info("✓ 集群 {} 已注册: id={}", name, id);
        return toResponse(physicalClusterMapper.findById(id).orElseThrow());
    }

    public List<PhysicalClusterResponse> list() {
        List<PhysicalClusterResponse> result = new java.util.ArrayList<>();
        for (PhysicalCluster cluster : physicalClusterMapper.findAll()) {
            result.add(toResponse(cluster));
        }
        return result;
    }

    public PhysicalClusterResponse getById(String id) {
        return toResponse(physicalClusterMapper.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("集群不存在: " + id)));
    }

    @Transactional
    public void delete(String id) {
        if (physicalClusterMapper.findById(id).isEmpty()) {
            throw new ResourceNotFoundException("集群不存在: " + id);
        }
        clientManager.closeClient(id);
        physicalClusterMapper.deleteById(id);
        log.info("✓ 集群已删除: {}", id);
    }

    private String decodeIfBase64(String s) {
        if (s == null) {
            return null;
        }
        if (s.contains("apiVersion") || s.contains("clusters:")) {
            return s;
        }
        try {
            return new String(Base64.getDecoder().decode(s), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }

    private PhysicalClusterResponse toResponse(PhysicalCluster c) {
        return PhysicalClusterResponse.builder()
                .id(c.getId())
                .name(c.getName())
                .description(c.getDescription())
                .status(c.getStatus())
                .kubernetesVersion(c.getKubernetesVersion())
                .nodeCount(c.getNodeCount())
                .gpuCount(c.getGpuCount())
                .lastSyncAt(c.getLastSyncAt())
                .syncMessage(c.getSyncMessage())
                .createdAt(c.getCreatedAt())
                .updatedAt(c.getUpdatedAt())
                .build();
    }
}
