package com.acmp.compute.service;

import com.acmp.compute.dto.GpuInfoView;
import com.acmp.compute.dto.GpuSplitView;
import com.acmp.compute.dto.NodeView;
import com.acmp.compute.dto.ScanResult;
import com.acmp.compute.entity.PhysicalCluster;
import com.acmp.compute.exception.ResourceNotFoundException;
import com.acmp.compute.k8s.KubernetesClientManager;
import com.acmp.compute.mapper.PhysicalClusterMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 显卡库存 / 集群扫描服务。
 *
 * <p>从 K8s 节点的 labels / annotations / allocatable 提取：
 * <ul>
 *   <li>GPU 型号（按节点聚合）</li>
 *   <li>HAMi vGPU 切分规格（从 virtualization-group-* 注解解析）</li>
 *   <li>CPU/Mem 上限</li>
 * </ul>
 *
 * <p>{@link #scanAndPersist(String)} 触发扫描并把结果回写到 physical_cluster 表。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GpuInventoryService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final KubernetesClientManager clientManager;
    private final PhysicalClusterMapper physicalClusterMapper;

    public List<NodeView> listNodes(String clusterId) {
        ensureCluster(clusterId);
        List<Node> nodes = clientManager.getClient(clusterId).nodes().list().getItems();
        return nodes.stream().map(this::toNodeView).collect(Collectors.toList());
    }

    public List<GpuInfoView> listGpus(String clusterId) {
        ensureCluster(clusterId);
        List<Node> nodes = clientManager.getClient(clusterId).nodes().list().getItems();
        Map<String, GpuInfoView> agg = new LinkedHashMap<>();
        for (Node n : nodes) {
            String model = readGpuModel(n);
            if (model == null) continue;
            long mem = readGpuMemoryMb(n);
            int cards = (int) readAllocatableLong(n, "nvidia.com/gpu");
            GpuInfoView v = agg.computeIfAbsent(model, k -> GpuInfoView.builder()
                    .model(k).memoryMb(mem).nodeCount(0).totalCards(0).nodeNames(new ArrayList<>()).build());
            v.setNodeCount(v.getNodeCount() + 1);
            v.setTotalCards(v.getTotalCards() + cards);
            v.getNodeNames().add(n.getMetadata().getName());
            if (mem > 0) v.setMemoryMb(mem);
        }
        return new ArrayList<>(agg.values());
    }

    public List<GpuSplitView> listGpuSplits(String clusterId) {
        ensureCluster(clusterId);
        List<Node> nodes = clientManager.getClient(clusterId).nodes().list().getItems();
        Map<String, GpuSplitView> agg = new LinkedHashMap<>();
        for (Node n : nodes) {
            String nodeName = n.getMetadata().getName();
            var ann = n.getMetadata().getAnnotations();
            if (ann == null) continue;
            for (var e : ann.entrySet()) {
                String key = e.getKey();
                if (!key.startsWith("nvidia.com/virtualization-group-")) continue;
                String group = key.substring("nvidia.com/virtualization-group-".length());
                int[] memCores = parseMemCores(e.getValue());
                if (memCores == null) continue;
                String poolLabel = "nvidia-" + group;
                GpuSplitView v = agg.computeIfAbsent(poolLabel, k -> GpuSplitView.builder()
                        .poolLabel(k).memMb(0).coresPct(0).nodeCount(0).nodeNames(new ArrayList<>()).build());
                v.setMemMb(memCores[0]);
                v.setCoresPct(memCores[1]);
                if (!v.getNodeNames().contains(nodeName)) {
                    v.getNodeNames().add(nodeName);
                    v.setNodeCount(v.getNodeNames().size());
                }
            }
        }
        return new ArrayList<>(agg.values());
    }

    /**
     * 触发扫描并回写 physical_cluster：
     *   - gpuTypes（型号 CSV）
     *   - hamiSplits（JSON 数组）
     *   - maxCpuCores / maxMemoryGib（取各节点 allocatable 最大值）
     */
    @Transactional
    public ScanResult scanAndPersist(String clusterId) {
        PhysicalCluster cluster = ensureCluster(clusterId);
        List<Node> nodes = clientManager.getClient(clusterId).nodes().list().getItems();

        List<GpuInfoView> gpus = aggregateGpus(nodes);
        List<GpuSplitView> splits = aggregateSplits(nodes);

        int maxCpu = 0;
        long maxMemBytes = 0L;
        for (Node n : nodes) {
            int cpu = (int) readAllocatableLong(n, "cpu");
            if (cpu > maxCpu) maxCpu = cpu;
            long mem = readAllocatableLong(n, "memory");
            if (mem > maxMemBytes) maxMemBytes = mem;
        }
        int maxMemGib = (int) (maxMemBytes / (1024L * 1024L * 1024L));

        String gpuTypesCsv = gpus.stream().map(GpuInfoView::getModel)
                .filter(Objects::nonNull).distinct().collect(Collectors.joining(","));
        String splitsJson = toJson(splits);

        PhysicalCluster patch = PhysicalCluster.builder()
                .id(clusterId)
                .gpuTypes(gpuTypesCsv)
                .hamiSplits(splitsJson)
                .maxCpuCores(maxCpu)
                .maxMemoryGib(maxMemGib)
                .build();
        physicalClusterMapper.updateScanSummary(patch);

        log.info("✓ 集群 {} 扫描完成: nodes={}, gpuModels={}, splits={}",
                clusterId, nodes.size(), gpus.size(), splits.size());

        return ScanResult.builder()
                .scannedAt(Instant.now())
                .nodeCount(nodes.size())
                .gpuModelCount(gpus.size())
                .splitCount(splits.size())
                .maxCpuCores(maxCpu)
                .maxMemoryGib(maxMemGib)
                .gpuTypes(gpus.stream().map(GpuInfoView::getModel).collect(Collectors.toList()))
                .splits(splits)
                .build();
    }

    // ─── helpers ───
    private PhysicalCluster ensureCluster(String clusterId) {
        return physicalClusterMapper.findById(clusterId)
                .orElseThrow(() -> new ResourceNotFoundException("集群不存在: " + clusterId));
    }

    private List<GpuInfoView> aggregateGpus(List<Node> nodes) {
        Map<String, GpuInfoView> agg = new LinkedHashMap<>();
        for (Node n : nodes) {
            String model = readGpuModel(n);
            if (model == null) continue;
            int cards = (int) readAllocatableLong(n, "nvidia.com/gpu");
            long mem = readGpuMemoryMb(n);
            GpuInfoView v = agg.computeIfAbsent(model, k -> GpuInfoView.builder()
                    .model(k).memoryMb(mem).nodeCount(0).totalCards(0).nodeNames(new ArrayList<>()).build());
            v.setNodeCount(v.getNodeCount() + 1);
            v.setTotalCards(v.getTotalCards() + cards);
            v.getNodeNames().add(n.getMetadata().getName());
            if (v.getMemoryMb() == null || v.getMemoryMb() == 0) v.setMemoryMb(mem);
        }
        return new ArrayList<>(agg.values());
    }

    private List<GpuSplitView> aggregateSplits(List<Node> nodes) {
        Map<String, GpuSplitView> agg = new LinkedHashMap<>();
        for (Node n : nodes) {
            String nodeName = n.getMetadata().getName();
            var ann = n.getMetadata().getAnnotations();
            if (ann == null) continue;
            for (var e : ann.entrySet()) {
                if (!e.getKey().startsWith("nvidia.com/virtualization-group-")) continue;
                String group = e.getKey().substring("nvidia.com/virtualization-group-".length());
                int[] mc = parseMemCores(e.getValue());
                if (mc == null) continue;
                String poolLabel = "nvidia-" + group;
                GpuSplitView v = agg.computeIfAbsent(poolLabel, k -> GpuSplitView.builder()
                        .poolLabel(k).memMb(0).coresPct(0).nodeCount(0).nodeNames(new ArrayList<>()).build());
                v.setMemMb(mc[0]);
                v.setCoresPct(mc[1]);
                if (!v.getNodeNames().contains(nodeName)) v.getNodeNames().add(nodeName);
                v.setNodeCount(v.getNodeNames().size());
            }
        }
        return new ArrayList<>(agg.values());
    }

    private NodeView toNodeView(Node n) {
        String name = n.getMetadata().getName();
        String status = n.getStatus() != null ? n.getStatus().getPhase() : "Unknown";
        var labels = n.getMetadata().getLabels();
        var taints = n.getSpec() != null ? n.getSpec().getTaints() : null;
        return NodeView.builder()
                .name(name)
                .status(status)
                .gpuModel(readGpuModel(n))
                .gpuCount((int) readAllocatableLong(n, "nvidia.com/gpu"))
                .cpuCores((int) readAllocatableLong(n, "cpu"))
                .memoryGiB((int) (readAllocatableLong(n, "memory") / (1024L * 1024L * 1024L)))
                .labelsJson(safeJson(labels))
                .taintsJson(safeJson(taints))
                .splits(extractNodeSplits(n))
                .build();
    }

    private List<GpuSplitView> extractNodeSplits(Node n) {
        List<GpuSplitView> result = new ArrayList<>();
        var ann = n.getMetadata().getAnnotations();
        if (ann == null) return result;
        for (var e : ann.entrySet()) {
            if (!e.getKey().startsWith("nvidia.com/virtualization-group-")) continue;
            String group = e.getKey().substring("nvidia.com/virtualization-group-".length());
            int[] mc = parseMemCores(e.getValue());
            if (mc == null) continue;
            result.add(GpuSplitView.builder()
                    .poolLabel("nvidia-" + group)
                    .memMb(mc[0])
                    .coresPct(mc[1])
                    .nodeCount(1)
                    .nodeNames(List.of(n.getMetadata().getName()))
                    .build());
        }
        return result;
    }

    private String readGpuModel(Node n) {
        var labels = n.getMetadata().getLabels();
        if (labels == null) return null;
        String m = labels.get("nvidia.com/gpu.product");
        if (m != null) return m;
        m = labels.get("nvidia.com/gpu.family");
        if (m != null) return m;
        return null;
    }

    private long readGpuMemoryMb(Node n) {
        var ann = n.getMetadata().getAnnotations();
        if (ann == null) return 0L;
        String s = ann.get("nvidia.com/gpu-memory");
        if (s == null) return 0L;
        try { return Long.parseLong(s.replaceAll("[^0-9]", "")); }
        catch (Exception e) { return 0L; }
    }

    private long readAllocatableLong(Node n, String key) {
        if (n.getStatus() == null || n.getStatus().getAllocatable() == null) return 0L;
        Quantity q = n.getStatus().getAllocatable().get(key);
        if (q == null) return 0L;
        try {
            Object amount = q.getAmount();
            if (amount instanceof Number) return ((Number) amount).longValue();
        } catch (Exception ignored) {}
        try { return Quantity.getAmountInBytes(q).longValue(); } catch (Exception ignored) {}
        return 0L;
    }

    private int[] parseMemCores(String value) {
        if (value == null) return null;
        try {
            String[] parts = value.split(",");
            int mem = Integer.parseInt(parts[0].trim());
            int cores = Integer.parseInt(parts[1].trim());
            return new int[]{mem, cores};
        } catch (Exception e) {
            log.warn("解析 HAMi 切分注解失败: {}", value);
            return null;
        }
    }

    private String safeJson(Object obj) {
        if (obj == null) return null;
        try { return MAPPER.writeValueAsString(obj); } catch (Exception e) { return null; }
    }

    private String toJson(Object obj) {
        return safeJson(obj);
    }
}
