package com.acmp.compute.service;

import com.acmp.compute.entity.ClusterNode;
import com.acmp.compute.entity.GpuBrand;
import com.acmp.compute.entity.GpuDevice;
import com.acmp.compute.entity.PhysicalCluster;
import com.acmp.compute.exception.ResourceNotFoundException;
import com.acmp.compute.k8s.KubernetesClientManager;
import com.acmp.compute.mapper.ClusterNodeMapper;
import com.acmp.compute.mapper.GpuDeviceMapper;
import com.acmp.compute.mapper.PhysicalClusterMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kubernetes.client.custom.Quantity;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.apis.VersionApi;
import io.kubernetes.client.openapi.models.V1Node;
import io.kubernetes.client.openapi.models.V1NodeAddress;
import io.kubernetes.client.openapi.models.V1NodeCondition;
import io.kubernetes.client.openapi.models.V1NodeList;
import io.kubernetes.client.openapi.models.VersionInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ClusterInventoryService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final KubernetesClientManager clientManager;
    private final PhysicalClusterMapper clusterMapper;
    private final ClusterNodeMapper nodeMapper;
    private final GpuDeviceMapper gpuMapper;

    /**
     * Node 列表是同步的核心事实。只有 listNode 成功后才把旧库存标记为 OFFLINE，
     * 避免网络临时故障把一套原本正常的库存错误覆盖。
     */
    @Transactional
    public PhysicalCluster sync(String clusterId) {
        PhysicalCluster cluster = clusterMapper.findById(clusterId).orElse(null);
        if (cluster == null) {
            throw new ResourceNotFoundException("集群不存在: " + clusterId);
        }
        try {
            ApiClient client = clientManager.getClient(clusterId);
            V1NodeList nodeList = new CoreV1Api(client).listNode().execute();
            VersionInfo version = new VersionApi(client).getCode().execute();
            List<V1Node> nodes = nodeList == null ? null : nodeList.getItems();
            if (nodes == null) {
                nodes = new ArrayList<>();
            }

            nodeMapper.markOfflineByCluster(clusterId);
            gpuMapper.markOfflineByCluster(clusterId);

            int gpuTotal = 0;
            for (V1Node source : nodes) {
                ClusterNode node = toNode(clusterId, source);
                Optional<ClusterNode> oldNode = nodeMapper.findByClusterAndName(clusterId, node.getName());
                if (oldNode.isPresent()) {
                    node.setId(oldNode.get().getId());
                    nodeMapper.updateDiscovered(node);
                } else {
                    nodeMapper.insert(node);
                }
                gpuTotal += syncGpus(node, source);
            }

            cluster.setStatus("ACTIVE");
            cluster.setKubernetesVersion(version == null ? null : version.getGitVersion());
            cluster.setNodeCount(nodes.size());
            cluster.setGpuCount(gpuTotal);
            cluster.setLastSyncAt(java.time.Instant.now());
            cluster.setSyncMessage("同步成功: nodes=" + nodes.size() + ", gpus=" + gpuTotal);
            clusterMapper.update(cluster);
            return clusterMapper.findById(clusterId).orElseThrow();
        } catch (ApiException e) {
            cluster.setStatus("ERROR");
            cluster.setSyncMessage(apiError(e));
            clusterMapper.update(cluster);
            throw new IllegalStateException(cluster.getSyncMessage(), e);
        } catch (RuntimeException e) {
            cluster.setStatus("ERROR");
            cluster.setSyncMessage(shortMessage("Kubernetes 连接或同步失败", e.getMessage()));
            clusterMapper.update(cluster);
            throw e;
        }
    }

    public List<ClusterNode> listNodes(String clusterId) {
        if (clusterMapper.findById(clusterId).isEmpty()) {
            throw new ResourceNotFoundException("集群不存在: " + clusterId);
        }
        return nodeMapper.findByClusterId(clusterId);
    }

    public List<GpuDevice> listGpusByCluster(String clusterId) {
        if (clusterMapper.findById(clusterId).isEmpty()) {
            throw new ResourceNotFoundException("集群不存在: " + clusterId);
        }
        return gpuMapper.findByClusterId(clusterId);
    }

    public List<GpuDevice> listGpusByNode(String nodeId) {
        if (nodeMapper.findById(nodeId).isEmpty()) {
            throw new ResourceNotFoundException("节点不存在: " + nodeId);
        }
        return gpuMapper.findByNodeId(nodeId);
    }

    /**
     * 查询指定集群中某个真实 Node 的 GPU，避免混用其他集群的 nodeId。
     */
    public List<GpuDevice> listGpusByNode(String clusterId, String nodeId) {
        ClusterNode node = nodeMapper.findById(nodeId)
                .orElseThrow(() -> new ResourceNotFoundException("节点不存在: " + nodeId));
        if (!clusterId.equals(node.getClusterId())) {
            throw new ResourceNotFoundException("该集群中不存在节点: " + nodeId);
        }
        return gpuMapper.findByNodeId(nodeId);
    }

    private ClusterNode toNode(String clusterId, V1Node source) {
        String name = source.getMetadata() == null ? "unknown" : source.getMetadata().getName();
        Map<String, Quantity> allocatable = source.getStatus() == null ? null : source.getStatus().getAllocatable();
        AcceleratorInventory accelerator = accelerator(allocatable);
        return ClusterNode.builder()
                .id(stableId("node", clusterId, name))
                .clusterId(clusterId)
                .name(name)
                .internalIp(internalIp(source))
                .cpuCores((int) quantity(allocatable, "cpu"))
                .memoryBytes(quantity(allocatable, "memory"))
                .gpuCount(accelerator.count)
                .status(nodeStatus(source))
                .labelsJson(json(source.getMetadata() == null ? null : source.getMetadata().getLabels()))
                .taintsJson(json(source.getSpec() == null ? null : source.getSpec().getTaints()))
                .lastSyncAt(java.time.Instant.now())
                .build();
    }

    /**
     * 拓扑只展示 Kubernetes Node 上报的 InternalIP，不推测宿主机地址。
     */
    private String internalIp(V1Node node) {
        if (node.getStatus() == null || node.getStatus().getAddresses() == null) {
            return null;
        }
        for (V1NodeAddress address : node.getStatus().getAddresses()) {
            if (address != null && "InternalIP".equals(address.getType())) {
                return address.getAddress();
            }
        }
        return null;
    }

    private int syncGpus(ClusterNode node, V1Node source) {
        Map<String, Quantity> allocatable = source.getStatus() == null ? null : source.getStatus().getAllocatable();
        AcceleratorInventory accelerator = accelerator(allocatable);
        int count = accelerator.count;
        Map<String, String> labels = source.getMetadata() == null ? null : source.getMetadata().getLabels();
        Map<String, String> annotations = source.getMetadata() == null ? null : source.getMetadata().getAnnotations();
        String model = first(labels, "nvidia.com/gpu.product", "nvidia.com/gpu.family",
                "accelerator", "huawei.com/ascend-model", "hygon.com/dcu.product", "amd.com/gpu.product");
        if ((model == null || model.isBlank()) && accelerator.model != null) {
            model = accelerator.model;
        }
        String driver = first(labels, "nvidia.com/cuda.driver-version.full",
                "nvidia.com/cuda.driver.major", "hygon.com/driver-version", "huawei.com/driver-version");
        String cuda = first(labels, "nvidia.com/cuda.runtime-version.full", "nvidia.com/cuda.runtime.major");
        Long memoryMb = number(first(annotations, "nvidia.com/gpu-memory", "gpu-memory-mb",
                "hygon.com/dcu-memory", "huawei.com/ascend-memory"));

        for (int index = 0; index < count; index++) {
            Optional<GpuDevice> old = gpuMapper.findByIdentity(node.getClusterId(), node.getName(), index);
            GpuDevice device = GpuDevice.builder()
                    .id(stableId("gpu", node.getClusterId(), node.getName(), String.valueOf(index)))
                    .clusterId(node.getClusterId()).nodeId(node.getId()).nodeName(node.getName())
                    .gpuIndex(index).gpuBrand(accelerator.brand).gpuModel(model).memoryMb(memoryMb)
                    .driverVersion(driver).cudaVersion(cuda)
                    .status("READY".equals(node.getStatus()) ? "READY" : "OFFLINE")
                    .usageStatus("IDLE").lastSyncAt(java.time.Instant.now()).build();
            if (old.isPresent()) {
                device.setId(old.get().getId());
                device.setResourcePoolId(old.get().getResourcePoolId());
                device.setUsageStatus(old.get().getUsageStatus());
                gpuMapper.updateDiscovered(device);
            } else {
                gpuMapper.insert(device);
            }
        }
        return count;
    }

    private AcceleratorInventory accelerator(Map<String, Quantity> values) {
        int nvidia = (int) quantity(values, "nvidia.com/gpu");
        int hygon = (int) firstPositiveQuantity(values,
                "hygon.com/dcunum", "hygon.com/dcu", "amd.com/dcu", "amd.com/gpu");
        int ascend = 0;
        String ascendModel = null;
        if (values != null) {
            for (Map.Entry<String, Quantity> entry : values.entrySet()) {
                String key = entry.getKey();
                if (key != null
                        && (key.startsWith("huawei.com/Ascend") || key.startsWith("huawei.com/ascend"))
                        && !key.endsWith("-memory")
                        && !key.endsWith("-core")) {
                    ascend += quantity(entry.getValue());
                    if (ascendModel == null && key.contains("/")) {
                        ascendModel = key.substring(key.indexOf('/') + 1);
                    }
                }
            }
        }

        int brands = (nvidia > 0 ? 1 : 0) + (hygon > 0 ? 1 : 0) + (ascend > 0 ? 1 : 0);
        if (brands > 1) {
            log.warn("节点同时暴露多个加速器品牌资源，MVP 按 NVIDIA、海光、华为优先级识别");
        }
        if (nvidia > 0) {
            return new AcceleratorInventory(GpuBrand.NVIDIA, nvidia, null);
        }
        if (hygon > 0) {
            return new AcceleratorInventory(GpuBrand.HYGON, hygon, null);
        }
        if (ascend > 0) {
            return new AcceleratorInventory(GpuBrand.HUAWEI_ASCEND, ascend, ascendModel);
        }
        return new AcceleratorInventory(null, 0, null);
    }

    private long quantity(Map<String, Quantity> values, String key) {
        if (values == null || values.get(key) == null) {
            return 0L;
        }
        try {
            return values.get(key).getNumber().longValue();
        } catch (Exception e) {
            return 0L;
        }
    }

    private long quantity(Quantity value) {
        if (value == null) {
            return 0L;
        }
        try {
            return value.getNumber().longValue();
        } catch (Exception e) {
            return 0L;
        }
    }

    private long firstPositiveQuantity(Map<String, Quantity> values, String... keys) {
        for (String key : keys) {
            long value = quantity(values, key);
            if (value > 0) {
                return value;
            }
        }
        return 0L;
    }

    private static final class AcceleratorInventory {
        private final GpuBrand brand;
        private final int count;
        private final String model;

        private AcceleratorInventory(GpuBrand brand, int count, String model) {
            this.brand = brand;
            this.count = count;
            this.model = model;
        }
    }

    private String nodeStatus(V1Node node) {
        if (node.getStatus() == null || node.getStatus().getConditions() == null) {
            return "UNKNOWN";
        }
        for (V1NodeCondition condition : node.getStatus().getConditions()) {
            if ("Ready".equals(condition.getType())) {
                return "True".equalsIgnoreCase(condition.getStatus()) ? "READY" : "NOT_READY";
            }
        }
        return "UNKNOWN";
    }

    private String first(Map<String, String> values, String... keys) {
        if (values == null) {
            return null;
        }
        for (String key : keys) {
            String value = values.get(key);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private Long number(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(value.replaceAll("[^0-9]", ""));
        } catch (Exception e) {
            return null;
        }
    }

    private String json(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("Kubernetes 字段 JSON 序列化失败: {}", e.getMessage());
            return null;
        }
    }

    private String stableId(String... parts) {
        String value = String.join(":", parts);
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private String apiError(ApiException e) {
        if (e.getCode() == 401) {
            return "Kubernetes 认证失败(401)";
        }
        if (e.getCode() == 403) {
            return "Kubernetes 无 Node 查询权限(403)";
        }
        return shortMessage("Kubernetes API 调用失败(" + e.getCode() + ")", e.getResponseBody());
    }

    private String shortMessage(String prefix, String detail) {
        String text = detail == null ? "" : detail;
        if (text.length() > 700) {
            text = text.substring(0, 700);
        }
        return prefix + (text.isEmpty() ? "" : ": " + text);
    }
}
