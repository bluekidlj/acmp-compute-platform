package com.acmp.compute.service;

import com.acmp.compute.dto.CapacityResponse;
import com.acmp.compute.dto.PhysicalClusterResponse;
import com.acmp.compute.entity.PhysicalCluster;
import com.acmp.compute.exception.ResourceNotFoundException;
import com.acmp.compute.k8s.KubernetesClientManager;
import com.acmp.compute.mapper.PhysicalClusterMapper;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 物理集群服务：注册、列表、容量查询、删除。
 * 注册时校验 kubeconfig 连通性并加密存储，客户端缓存由 KubernetesClientManager 管理。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PhysicalClusterService {

    private final PhysicalClusterMapper physicalClusterMapper;
    private final EncryptionService encryptionService;
    private final KubernetesClientManager clientManager;

    /**
     * 注册物理集群：校验 kubeconfig 连通性 → 加密存储 → 写入 DB → 缓存客户端。
     */
    @Transactional(rollbackFor = Exception.class)
    public PhysicalClusterResponse register(String name, String kubeconfigBase64) {
        return register(name, kubeconfigBase64, "NVIDIA", "default");
    }

    @Transactional(rollbackFor = Exception.class)
    public PhysicalClusterResponse register(String name, String kubeconfigBase64, String gpuTypes) {
        return register(name, kubeconfigBase64, gpuTypes, "default");
    }

    @Transactional(rollbackFor = Exception.class)
    public PhysicalClusterResponse register(String name, String kubeconfigBase64, String gpuTypes, String location) {
        // 若前端传的是 Base64 编码的 kubeconfig 字符串，需先解码得到原始内容再校验
        String plainKubeconfig = kubeconfigBase64;
        if (!plainKubeconfig.contains("apiVersion") && !plainKubeconfig.contains("clusters:")) {
            try {
                plainKubeconfig = new String(java.util.Base64.getDecoder().decode(kubeconfigBase64), java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception ignored) {
                // 当作明文处理
            }
        }
        if (!clientManager.validateKubeconfig(plainKubeconfig)) {
            throw new IllegalArgumentException("kubeconfig 校验失败，无法连接集群");
        }
        String encrypted = encryptionService.encrypt(plainKubeconfig);
        String id = UUID.randomUUID().toString();
        PhysicalCluster cluster = PhysicalCluster.builder()
                .id(id)
                .name(name)
                .gpuTypes(gpuTypes)
                .location(location)
                .kubeconfigBase64Encrypted(encrypted)
                .status("active")
                .build();
        physicalClusterMapper.insert(cluster);
        // 触发缓存（下次 getClient 时即可用）
        clientManager.getClient(id);
        return toResponse(physicalClusterMapper.findById(id).orElseThrow());
    }

    /** 将 fabric8 Quantity 解析为 long（兼容不同版本 API） */
    private long parseQuantityAsLong(Quantity q) {
        if (q == null) return 0L;
        try {
            Object amount = q.getAmount();
            if (amount instanceof Number) return ((Number) amount).longValue();
        } catch (Exception ignored) {}
        return 0L;
    }

    public List<PhysicalClusterResponse> list() {
        return physicalClusterMapper.findAll().stream().map(this::toResponse).collect(Collectors.toList());
    }

    /**
     * 实时汇总集群容量：遍历节点 allocatable 中的 nvidia.com/gpu、cpu、memory。
     */
    public CapacityResponse getCapacity(String id) {
        KubernetesClient client = clientManager.getClient(id);
        AtomicLong gpuTotal = new AtomicLong(0);
        AtomicLong cpuTotal = new AtomicLong(0);
        AtomicLong memoryTotal = new AtomicLong(0);
        List<Node> nodes = client.nodes().list().getItems();
        for (Node node : nodes) {
            if (node.getStatus() == null || node.getStatus().getAllocatable() == null) continue;
            Map<String, Quantity> allocatable = node.getStatus().getAllocatable();
            gpuTotal.addAndGet(parseQuantityAsLong(allocatable.get("nvidia.com/gpu")));
            cpuTotal.addAndGet(parseQuantityAsLong(allocatable.get("cpu")));
            memoryTotal.addAndGet(parseQuantityAsLong(allocatable.get("memory")));
        }
        return CapacityResponse.builder()
                .gpuSlots(gpuTotal.get())
                .cpu(String.valueOf(cpuTotal.get()))
                .memory(String.valueOf(memoryTotal.get()))
                .build();
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(String id) {
        if (!physicalClusterMapper.findById(id).isPresent()) {
            throw new ResourceNotFoundException("集群不存在: " + id);
        }
        clientManager.closeClient(id);
        physicalClusterMapper.deleteById(id);
    }

    private PhysicalClusterResponse toResponse(PhysicalCluster c) {
        return PhysicalClusterResponse.builder()
                .id(c.getId())
                .name(c.getName())
                .description(c.getDescription())
                .status(c.getStatus())
                .totalGpuSlots(c.getTotalGpuSlots())
                .gpuTypes(c.getGpuTypes())
                .location(c.getLocation())
                .createdAt(c.getCreatedAt())
                .updatedAt(c.getUpdatedAt())
                .build();
    }
}
