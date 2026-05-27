package com.acmp.compute.entity;

/**
 * GPU 切分规格预设（HAMi vGPU）。
 *
 * 预置每种 GPU 型号的切分规格，用户在创建资源池时选择切分类型。
 * 平台自动生成 ComputeSpec，设置正确的 nodeSelector + gpumem/gpucores。
 *
 * 切分链路：
 * ComputeSpec.nodeSelector → PhysicalCluster.nodeLabels → Pod 调度到切分节点
 */
public enum GpuSplitSpec {

    // NVIDIA A100 80GB SXM
    NVIDIA_A100_80GB_1_2("nvidia-a100-80g-1/2", "NVIDIA", "A100-80GB-SXM", 40960, 50),   // 1/2 卡
    NVIDIA_A100_80GB_1_4("nvidia-a100-80g-1/4", "NVIDIA", "A100-80GB-SXM", 20480, 25),   // 1/4 卡
    NVIDIA_A100_80GB_1_8("nvidia-a100-80g-1/8", "NVIDIA", "A100-80GB-SXM", 10240, 12),   // 1/8 卡

    // NVIDIA RTX 4090 24GB
    NVIDIA_RTX4090_24G_1_2("nvidia-rtx4090-24g-1/2", "NVIDIA", "RTX4090-24GB", 12288, 50),
    NVIDIA_RTX4090_24G_1_4("nvidia-rtx4090-24g-1/4", "NVIDIA", "RTX4090-24GB", 6144, 25),
    NVIDIA_RTX4090_24G_1_8("nvidia-rtx4090-24g-1/8", "NVIDIA", "RTX4090-24GB", 3072, 12),

    // Hygon DCU 32GB
    HYGON_DCU_32G_1_2("hygon-dcu-32g-1/2", "HYGON", "Hygon-DCU-32GB", 16384, 50),
    HYGON_DCU_32G_1_4("hygon-dcu-32g-1/4", "HYGON", "Hygon-DCU-32GB", 8192, 25),
    HYGON_DCU_32G_1_8("hygon-dcu-32g-1/8", "HYGON", "Hygon-DCU-32GB", 4096, 12);

    private final String specName;       // ComputeSpec.name
    private final String gpuBrand;        // NVIDIA / HYGON
    private final String gpuType;         // HAMi 节点标签 value 前缀
    private final int gpumemMb;           // 每副本 GPU 显存 MB
    private final int gpucores;           // 每副本 GPU 算力 %

    GpuSplitSpec(String specName, String gpuBrand, String gpuType, int gpumemMb, int gpucores) {
        this.specName = specName;
        this.gpuBrand = gpuBrand;
        this.gpuType = gpuType;
        this.gpumemMb = gpumemMb;
        this.gpucores = gpucores;
    }

    public String getSpecName() { return specName; }
    public String getGpuBrand() { return gpuBrand; }
    public String getGpuType() { return gpuType; }
    public int getGpumemMb() { return gpumemMb; }
    public int getGpucores() { return gpucores; }

    /** 从 specName 反查枚举 */
    public static GpuSplitSpec fromSpecName(String name) {
        for (GpuSplitSpec s : values()) {
            if (s.specName.equals(name)) return s;
        }
        return null;
    }

    /** 从 gpuType + splitRatio 查找，如 "A100-80GB-SXM", "1/4" */
    public static GpuSplitSpec fromGpuTypeAndRatio(String gpuType, String splitRatio) {
        String specName = guessPrefix(gpuType) + "-" + splitRatio.replace("/", "");
        return fromSpecName(specName);
    }

    /**
     * 从 poolLabel（specName）解析切分比例。
     * 如 "nvidia-a100-80g-1/4" → "1/4"
     */
    public static String parseSplitType(String specName) {
        if (specName == null) return null;
        int idx = specName.lastIndexOf("-");
        if (idx > 0) {
            String suffix = specName.substring(idx + 1);
            if (suffix.matches("\\d+/\\d+")) return suffix;
            if (suffix.matches("\\d+")) {
                // 解析如 "14" → "1/4", "18" → "1/8"
                if (suffix.length() == 2) return "1/" + suffix.charAt(1);
                if (suffix.length() == 3 && suffix.startsWith("1")) return "1/" + suffix.charAt(1);
            }
        }
        return null;
    }

    private static String guessPrefix(String gpuType) {
        if (gpuType.contains("A100-80GB")) return "nvidia-a100-80g";
        if (gpuType.contains("RTX4090")) return "nvidia-rtx4090-24g";
        if (gpuType.contains("DCU")) return "hygon-dcu-32g";
        return gpuType.toLowerCase().replaceAll("[^a-z0-9]", "-");
    }
}