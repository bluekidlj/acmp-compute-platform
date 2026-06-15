package com.acmp.compute.service;

import com.acmp.compute.dto.CapacityResponse;
import com.acmp.compute.dto.PhysicalClusterResponse;
import com.acmp.compute.entity.GpuBrand;
import com.acmp.compute.entity.PhysicalCluster;
import com.acmp.compute.exception.ResourceNotFoundException;
import com.acmp.compute.k8s.KubernetesClientManager;
import com.acmp.compute.mapper.PhysicalClusterMapper;
import com.acmp.compute.security.EncryptionService;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PhysicalClusterService {

    private final PhysicalClusterMapper physicalClusterMapper;
    private final EncryptionService encryptionService;
    private final KubernetesClientManager clientManager;
    private final GpuInventoryService gpuInventoryService;

    @Transactional(rollbackFor = Exception.class)
    public PhysicalClusterResponse register(String name, String kubeconfigBase64, String gpuTypes,
                                            String location, String nodeLabelsJson, String taintsJson) {
        String plainKubeconfig = decodeIfBase64(kubeconfigBase64);
        if (!clientManager.validateKubeconfig(plainKubeconfig)) {
            throw new IllegalArgumentException("kubeconfig 校验失败，无法连接集群");
        }
        String encrypted = encryptionService.encrypt(plainKubeconfig);
        String id = UUID.randomUUID().toString();
        PhysicalCluster cluster = PhysicalCluster.builder()
                .id(id)
                .name(name)
                .gpuTypes(gpuTypes != null ? gpuTypes : GpuBrand.NVIDIA.name())
                .location(location != null ? location : "default")
                .nodeLabels(nodeLabelsJson)
                .taints(taintsJson)
                .kubeconfigBase64Encrypted(encrypted)
                .status("active")
                .build();
        physicalClusterMapper.insert(cluster);
        clientManager.getClient(id);
        log.info("✓ 集群 {} 已注册: id={}", name, id);
        return toResponse(physicalClusterMapper.findById(id).orElseThrow());
    }

    public List<PhysicalClusterResponse> list() {
        return physicalClusterMapper.findAll().stream()
                .map(this::toResponse).collect(Collectors.toList());
    }

    public PhysicalClusterResponse getById(String id) {
        return toResponse(physicalClusterMapper.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("集群不存在: " + id)));
    }

    public CapacityResponse getCapacity(String id) {
        KubernetesClient client = clientManager.getClient(id);
        AtomicLong gpuTotal = new AtomicLong(0);
        AtomicLong cpuTotal = new AtomicLong(0);
        AtomicLong memoryTotal = new AtomicLong(0);
        List<Node> nodes = client.nodes().list().getItems();
        for (Node node : nodes) {
            if (node.getStatus() == null || node.getStatus().getAllocatable() == null) continue;
            var alloc = node.getStatus().getAllocatable();
            gpuTotal.addAndGet(parseQuantity(alloc.get("nvidia.com/gpu")));
            cpuTotal.addAndGet(parseQuantity(alloc.get("cpu")));
            memoryTotal.addAndGet(parseQuantity(alloc.get("memory")));
        }
        return CapacityResponse.builder()
                .gpuSlots(gpuTotal.get())
                .cpu(String.valueOf(cpuTotal.get()))
                .memory(String.valueOf(memoryTotal.get()))
                .build();
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
        if (s == null) return null;
        if (s.contains("apiVersion") || s.contains("clusters:")) return s;
        try { return new String(Base64.getDecoder().decode(s), StandardCharsets.UTF_8); }
        catch (Exception e) { return s; }
    }

    private long parseQuantity(Quantity q) {
        if (q == null) return 0L;
        try {
            Object amount = q.getAmount();
            if (amount instanceof Number) return ((Number) amount).longValue();
        } catch (Exception ignored) {}
        try { return Quantity.getAmountInBytes(q).longValue(); } catch (Exception ignored) {}
        return 0L;
    }

    private PhysicalClusterResponse toResponse(PhysicalCluster c) {
        return PhysicalClusterResponse.builder()
                .id(c.getId())
                .name(c.getName())
                .description(c.getDescription())
                .status(c.getStatus())
                .gpuTypes(c.getGpuTypes())
                .location(c.getLocation())
                .hamiSplits(c.getHamiSplits())
                .maxCpuCores(c.getMaxCpuCores())
                .maxMemoryGib(c.getMaxMemoryGib())
                .createdAt(c.getCreatedAt())
                .updatedAt(c.getUpdatedAt())
                .build();
    }
}
