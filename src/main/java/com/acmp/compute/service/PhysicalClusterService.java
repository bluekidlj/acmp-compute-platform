package com.acmp.compute.service;

import com.acmp.compute.dto.CapacityResponse;
import com.acmp.compute.dto.PhysicalClusterResponse;
import com.acmp.compute.entity.PhysicalCluster;
import com.acmp.compute.entity.GpuBrand;
import com.acmp.compute.exception.ResourceNotFoundException;
import com.acmp.compute.k8s.KubernetesClientManager;
import com.acmp.compute.k8s.NodeInfoResponse;
import com.acmp.compute.k8s.NodeScanResponse;
import com.acmp.compute.mapper.PhysicalClusterMapper;
import com.acmp.compute.security.EncryptionService;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        return register(name, kubeconfigBase64, GpuBrand.NVIDIA.name(), "default");
    }

    @Transactional(rollbackFor = Exception.class)
    public PhysicalClusterResponse register(String name, String kubeconfigBase64, String gpuTypes) {
        return register(name, kubeconfigBase64, gpuTypes, "default");
    }

    @Transactional(rollbackFor = Exception.class)
    public PhysicalClusterResponse register(String name, String kubeconfigBase64, String gpuTypes, String location) {
        return register(name, kubeconfigBase64, gpuTypes, location, null, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public PhysicalClusterResponse register(String name, String kubeconfigBase64, String gpuTypes, String location,
                                            String nodeLabels, String taints) {
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
                .nodeLabels(nodeLabels)
                .taints(taints)
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

    /**
     * 扫描集群节点，收集节点算力信息（用于纳管展示）。
     * 返回节点列表 + 集群不重复的 poolLabel 枚举（用于资源池创建时选择切分规格）。
     */
    public NodeScanResponse scanNodes(String clusterId) {
        KubernetesClient client = clientManager.getClient(clusterId);
        List<Node> nodes = client.nodes().list().getItems();

        List<NodeInfoResponse> nodeInfos = nodes.stream().map(this::toNodeInfo).collect(Collectors.toList());

        // 汇总集群中所有不重复的 poolLabel（支持逗号分隔多规格）
        Set<String> poolLabels = new HashSet<>();
        for (Node n : nodes) {
            var labels = n.getMetadata().getLabels();
            if (labels != null) {
                String pl = labels.get("pool");
                if (pl != null && !pl.isEmpty()) {
                    // 支持逗号分隔多规格：pool=nvidia-a100-80g-1/2,nvidia-a100-80g-1/4
                    for (String spec : pl.split(",")) {
                        spec = spec.trim();
                        if (!spec.isEmpty()) {
                            poolLabels.add(spec);
                        }
                    }
                }
            }
        }

        return NodeScanResponse.builder()
                .nodes(nodeInfos)
                .poolLabels(poolLabels)
                .build();
    }

    /**
     * 注意：poolLabels 与 GPU type 的对应关系需外部保证。
     * poolLabels 只反映节点上有哪些 pool 标签值，
     * 不包含这些标签值属于哪个 GPU 型号（HAMI/AMD）。前端需根据节点列表推断。
     */
    private NodeInfoResponse toNodeInfo(Node node) {
        var labels = node.getMetadata().getLabels();
        var allocatable = node.getStatus() != null ? node.getStatus().getAllocatable() : null;

        // 支持逗号分隔多规格：pool=nvidia-a100-80g-1/2,nvidia-a100-80g-1/4
        Set<String> poolLabels = new HashSet<>();
        String poolLabelStr = labels != null ? labels.get("pool") : null;
        if (poolLabelStr != null && !poolLabelStr.isEmpty()) {
            for (String spec : poolLabelStr.split(",")) {
                spec = spec.trim();
                if (!spec.isEmpty()) {
                    poolLabels.add(spec);
                }
            }
        }

        String gpuType = labels != null ? labels.get("nvidia.com/gpu-family") : null;
        if (gpuType == null) gpuType = labels != null ? labels.get("amd.com/dcu-family") : null;

        int nodeCount = allocatable != null ? parseInt(allocatable.get("nvidia.com/gpu")) : 0;
        int nodeMemMb = allocatable != null ? parseInt(allocatable.get("nvidia.com/gpumem")) : 0;
        int nodeCores = allocatable != null ? parseInt(allocatable.get("nvidia.com/gpucores")) : 0;
        int cpuCores = allocatable != null ? parseInt(allocatable.get("cpu")) : 0;
        int memoryGiB = allocatable != null ? parseMemoryAsGiB(allocatable.get("memory")) : 0;

        return NodeInfoResponse.builder()
                .name(node.getMetadata().getName())
                .status(node.getStatus() != null ? node.getStatus().getPhase() : "Unknown")
                .gpuType(gpuType)
                .nodeCount(nodeCount)
                .nodeMemMb(nodeMemMb)
                .nodeCores(nodeCores)
                .cpuCores(cpuCores)
                .memoryGiB(memoryGiB)
                .poolLabels(poolLabels)
                .labelsJson(labels != null ? safeWriteValueAsString(labels) : "{}")
                .build();
    }

    private int parseInt(Quantity q) {
        if (q == null) return 0;
        try {
            Object amount = q.getAmount();
            if (amount instanceof Number) return ((Number) amount).intValue();
            return Integer.parseInt(q.getAmount().toString());
        } catch (Exception e) { return 0; }
    }

    private int parseMemoryAsGiB(Quantity q) {
        if (q == null) return 0;
        try {
            Object amount = q.getAmount();
            if (amount instanceof Number) {
                long bytes = ((Number) amount).longValue();
                return (int) (bytes / (1024 * 1024 * 1024));
            }
            String str = q.getAmount().toString();
            if (str.endsWith("Gi")) return Integer.parseInt(str.replace("Gi", "").trim());
            if (str.endsWith("Mi")) return Integer.parseInt(str.replace("Mi", "").trim()) / 1024;
            if (str.endsWith("Ki")) return Integer.parseInt(str.replace("Ki", "").trim()) / (1024 * 1024);
            return Integer.parseInt(str) / (1024 * 1024 * 1024);
        } catch (Exception e) { return 0; }
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

    private String safeWriteValueAsString(Object obj) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(obj);
        } catch (Exception e) {
            log.warn("JSON序列化失败: {}", obj, e);
            return "{}";
        }
    }
}
