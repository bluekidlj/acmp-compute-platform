# 技术交底书

**发明名称：** 一种异构GPU算力规格的统一描述与智能路由方法及系统

**申请类型：** 发明

**本专利发明人:** （待填写）

**技术交底书撰写人:** （待填写）

**技术问题联系人:** （待填写）

**联系人电话:** （待填写）

**联系人邮箱:** （待填写）

**术语解释：**

| 术语 | 含义 |
|---|---|
| 异构GPU | 指不同品牌、型号、架构的GPU设备，如NVIDIA A100、华为昇腾910B、海光DCU等 |
| 算力规格（ComputeSpec） | 描述GPU资源需求的标准化描述符，包含GPU数量、显存、算力占比等参数 |
| 算力原子单位（CU） | Compute Unit，归一化的算力计量单位，用于跨品牌GPU算力统一量化 |
| 规格归一化描述符 | 用统一维度描述不同品牌GPU能力的抽象描述，解耦用户需求与具体GPU品牌 |
| 等效规格组 | 将不同品牌但算力/显存能力相近的规格归为同一组，支持跨品牌替代 |
| 智能路由 | 根据用户需求自动匹配最优GPU品牌、切分比例和资源池的调度算法 |
| 资源池（ResourcePool） | 按使用方式（独占/共享/超分）划分的GPU资源集合，绑定到租户工作空间 |
| K8s资源键 | Kubernetes中标识GPU设备的资源名称，如nvidia.com/gpu、amd.com/dcu |

---

## 一、介绍背景技术，并描述已有的与本发明最相近似的现有技术方案

### 1.1 技术背景

在AI算力管理平台中，K8s集群通常包含多种品牌的GPU设备。不同品牌GPU使用不同的K8s设备资源键进行标识：NVIDIA使用`nvidia.com/gpu`，海光DCU使用`amd.com/dcu`，华为昇腾使用`huawei.com/ascend910`。当用户需要部署AI推理服务时，需要选择一个"算力规格"（ComputeSpec），该规格决定了Pod将请求哪种GPU、多少显存、多少算力。

### 1.2 现有技术方案一：品牌绑定式规格定义

当前主流方案（如ACMP-Compute平台）采用品牌绑定式规格定义。每个规格（ComputeSpec）硬编码了`gpuBrand`字段，直接决定了K8s资源键的映射关系：

```
规格定义示例：
- exclusive-nvidia-a100-80g → gpuBrand=NVIDIA, resourceKey=nvidia.com/gpu
- exclusive-hygon-dcu      → gpuBrand=HYGON, resourceKey=amd.com/dcu
- exclusive-huawei-910b    → gpuBrand=HUAWEI_ASCEND, resourceKey=huawei.com/ascend910
```

规格与池类型通过`specType→poolType`路由：PHYSICAL规格路由到EXCLUSIVE池，VIRTUAL规格路由到SHARED池。

**代表性实现：** ACMP-Compute 1.0中的`K8sResourceBuilder.gpuResourceKey(GpuBrand)`方法，通过switch-case硬编码品牌到资源键的映射。

### 1.3 现有技术方案二：HAMi vGPU切分规格

HAMi（Heterogeneous AI Computing Virtualization Middleware）提供了NVIDIA GPU的vGPU切分能力，通过`nvidia.com/gpumem`和`nvidia.com/gpucores`两个扩展资源键控制显存和算力的分配比例。切分规格预定义为固定比例（1/2、1/4、1/8）。

**代表性实现：** ACMP-Compute中的`shared-hami-a100-1/2`、`shared-hami-a100-1/4`、`shared-hami-a100-1/8`规格。

### 1.4 现有技术方案三：Tessera核粒度异构GPU调度

Tessera（arXiv:2604.10180）提出了kernel粒度的异构GPU拆分调度，根据kernel的资源需求特征（计算密集/访存密集）将其分配到最适合的GPU类型上执行。通过PTX级依赖分析确保正确性，流水线执行模型重叠通信和计算。

### 1.5 现有技术方案四：HexGen-2异构GPU分离推理

HexGen-2（arXiv:2502.07903）在异构GPU环境下实现了prefill/decode分离部署，将调度问题建模为约束优化问题，使用图划分和最大流算法联合优化资源分配。

---

## 二、现有技术的缺点是什么？

### 2.1 品牌绑定式规格的扩展性差

现有方案中规格与GPU品牌强耦合。每新增一种GPU品牌（如寒武纪MLU），需要：
1. 在枚举中添加新品牌
2. 在代码中添加新的资源键映射（修改switch-case）
3. 手动创建该品牌对应的所有规格
4. 手动创建对应的资源池

**缺点：** 扩展成本高、需要修改代码重新部署、容易遗漏映射关系。

### 2.2 缺乏跨品牌的需求-资源匹配能力

用户部署推理服务时的真实需求是"我需要X GB显存、Y TFLOPS算力"，而非"我需要NVIDIA A100的1/4切分"。现有方案要求用户必须知道目标GPU的品牌和型号，手动选择规格名，无法根据需求自动匹配最合适的GPU。

**缺点：** 用户体验差、资源利用率低（可能选到非最优规格）、品牌锁定。

### 2.3 固定切分规格无法覆盖所有需求

HAMi的切分规格是2的幂次（1/2、1/4、1/8），但实际模型需求并非2的幂次。例如7B参数模型推理在A100上可能只需要约15%显存和20%算力，选择1/4切分会浪费10%显存，而1/8切分又不足。

**缺点：** 资源浪费或资源不足，无法精细匹配实际负载。

### 2.4 现有学术方案缺乏平台级规格抽象

Tessera和HexGen-2虽然在kernel级和推理阶段级实现了异构调度，但都是从"已有GPU资源后如何分配"的角度出发，缺乏"从用户需求反推最优GPU类型"的双向匹配机制，也不涉及平台级的多租户配额管理。

**缺点：** 仅解决调度层问题，不解决规格定义和需求匹配层的问题。

---

## 三、本发明解决的技术问题或技术目的？

1. **解耦规格与品牌：** 将算力规格从具体GPU品牌中解耦，用统一维度描述算力需求，支持品牌无关的规格定义
2. **需求驱动的智能路由：** 根据用户的实际算力/显存需求，自动匹配最优GPU品牌、切分比例和资源池，无需用户手动选择规格名
3. **跨品牌等效替代：** 当首选GPU品牌资源不足时，自动降级到等效品牌，保证服务可用性
4. **动态品牌发现：** 新GPU品牌接入时，无需修改代码，通过配置即可自动注册新的资源键映射

---

## 四、本发明技术方案的详细阐述

### 4.1 整体架构

```
用户需求描述                    归一化规格层                 品牌资源键层
┌──────────────┐           ┌──────────────┐          ┌──────────────┐
│ "20GB显存    │           │ 规格归一化   │          │ NVIDIA:      │
│  30CU算力    │ ───────→ │ 描述符       │ ───────→ │ nvidia.com/  │
│  可延迟<50ms"│           │ cu=30,mem=20 │          │ gpumem=20480 │
└──────────────┘           └──────┬───────┘          │ gpucores=30  │
                                  │                  ├──────────────┤
                                  │                  │ 华为:        │
                                  ├─────────────→   │ huawei.com/  │
                                  │                  │ ascend910=1  │
                                  │                  ├──────────────┤
                                  │                  │ 海光:        │
                                  └─────────────→   │ amd.com/     │
                                                     │ dcu=1        │
                                                     └──────────────┘
```

### 4.2 算力原子单位（CU）定义与换算

**CU定义：** 1 CU = 基准GPU（NVIDIA A100-80G SXM4）1%的FP16算力，即约3.12 TFLOPS。

**CU换算表（存储于数据库，可动态配置）：**

| GPU型号 | CU值 | 显存(GB) | FP16算力(TFLOPS) | 品牌资源键 |
|---|---|---|---|---|
| NVIDIA A100-80G | 100 | 80 | 312 | nvidia.com/gpu |
| NVIDIA H100-80G | 160 | 80 | 499 | nvidia.com/gpu |
| 华为昇腾910B | 64 | 64 | 200 | huawei.com/ascend910 |
| 海光DCU Z100 | 48 | 32 | 150 | amd.com/dcu |
| 寒武纪MLU370 | 36 | 24 | 112 | cambricon.com/mlu |

换算表以数据库表`gpu_cu_table`存储，支持运行时动态新增品牌和型号，无需修改代码。

### 4.3 规格归一化描述符

将现有品牌绑定式规格改造为品牌无关的归一化描述符：

```
现有方式（品牌绑定）：
  name: shared-hami-a100-1/4
  gpuBrand: NVIDIA
  defaultGpuCount: 1
  defaultGpumemMb: 20480
  defaultGpucores: 25
  poolType: SHARED

归一化方式（品牌无关）：
  name: shared-medium-inference
  minCU: 20          // 最低需要20CU算力
  maxCU: 50          // 最高可用50CU算力
  minMemGB: 15       // 最低需要15GB显存
  maxMemGB: 40       // 最高可用40GB显存
  poolType: SHARED
  preferredBrands: [NVIDIA, HUAWEI]  // 偏好品牌，可选
  excludedBrands: []                   // 排除品牌，可选
```

**关键特征：** 规格不再包含任何品牌特定字段（gpuBrand、resourceQuotaKey等），品牌信息在路由阶段动态注入。

### 4.4 智能路由算法

当用户提交部署请求时，路由算法执行以下步骤：

**步骤1：需求解析**
```
输入：用户选择的规格名（如shared-medium-inference）
输出：归一化描述符 {minCU=20, maxCU=50, minMemGB=15, maxMemGB=40}
```

**步骤2：品牌匹配**
```
遍历集群中可用的GPU型号：
  对每个GPU型号G：
    计算G的可用CU = G.CU × 切分比例
    计算G的可用显存 = G.MEM × 切分比例
    如果 可用CU ≥ minCU 且 可用显存 ≥ minMemGB：
      将(G, 切分比例)加入候选列表
```

**步骤3：最优选择**
```
对候选列表排序，排序因子：
  1. 偏好品牌优先（preferredBrands）
  2. 资源浪费最小化：score = 1 - (可用CU - minCU) / maxCU
  3. 可用性优先：选择当前配额余量最大的
  4. 成本优先：选择CU单价最低的

选出最优(GPU型号, 切分比例)
```

**步骤4：资源键注入**
```
根据选出的GPU型号，查gpu_cu_table获取：
  - 品牌资源键（如nvidia.com/gpu）
  - 扩展资源键（如nvidia.com/gpumem, nvidia.com/gpucores）

动态生成K8s Pod资源请求：
  limits:
    nvidia.com/gpu: 1
    nvidia.com/gpumem: 20480      # 20GB
    nvidia.com/gpucores: 25       # 25%
    platform.io/shared-medium-inference: 1
```

**步骤5：配额校验与路由**
```
根据选出的品牌和切分比例，推导出实际poolType
找到租户工作空间下对应类型的池
校验项目配额：project.used + 1 ≤ project.total
预扣配额 → 提交K8s → 确认/回滚
```

### 4.5 等效规格映射与跨品牌降级

**等效规格组定义：**
```
等效组ID: eg-high-inference
成员规格:
  - {brand: NVIDIA, model: A100-80G, split: 1/4, cu: 25, mem: 20}
  - {brand: HUAWEI, model: 910B, split: 1/3, cu: 21, mem: 21}
  - {brand: HYGON, model: Z100, split: 1/2, cu: 24, mem: 16}
等效度排序: NVIDIA(1.0) > HUAWEI(0.85) > HYGON(0.7)
```

**降级流程：**
```
1. 首选品牌NVIDIA A100配额不足
2. 查等效组，找次优品牌HUAWEI 910B
3. 计算性能影响：等效度0.85，预计延迟增加15%
4. 如果用户可容忍（maxLatency允许）：
   - 使用HUAWEI 910B的1/3切分创建部署
   - 自动调整K8s资源键为huawei.com/ascend910
   - 自动调整nodeSelector指向昇腾节点
5. 如果不可容忍：返回错误，提示等待NVIDIA资源
```

### 4.6 动态品牌发现与注册

**品牌注册表（数据库表`gpu_brand_registry`）：**
```
brand_code: NVDIA
display_name: NVIDIA GPU
resource_key_pattern: nvidia.com/gpu
supports_vgpu: true
vgpu_mem_key: nvidia.com/gpumem
vgpu_cores_key: nvidia.com/gpucores
scan_discovery_key: nvidia.com/gpu  // K8s节点上扫描此资源键来发现该品牌GPU
```

**自动发现流程：**
```
1. 管理员在品牌注册表中配置新品牌信息（无需改代码）
2. 集群扫描时，遍历所有注册品牌的scan_discovery_key
3. 如果节点上有该资源键的allocatable > 0，则发现该品牌GPU
4. 自动关联到gpu_cu_table中的型号，更新集群的gpu_types字段
5. 自动生成该品牌对应的默认规格（基于归一化描述符反向生成）
```

### 4.7 数据模型变更

**新增表：**
```sql
-- GPU品牌注册表
CREATE TABLE gpu_brand_registry (
    id VARCHAR(36) PRIMARY KEY,
    brand_code VARCHAR(64) NOT NULL UNIQUE,
    display_name VARCHAR(128),
    resource_key_pattern VARCHAR(128) NOT NULL,
    supports_vgpu BOOLEAN DEFAULT FALSE,
    vgpu_mem_key VARCHAR(128),
    vgpu_cores_key VARCHAR(128),
    scan_discovery_key VARCHAR(128)
);

-- GPU型号CU换算表
CREATE TABLE gpu_cu_table (
    id VARCHAR(36) PRIMARY KEY,
    brand_code VARCHAR(64) NOT NULL,
    model_name VARCHAR(128) NOT NULL,
    cu_value INT NOT NULL,
    memory_gb INT NOT NULL,
    fp16_tflops DECIMAL(10,2),
    UNIQUE(brand_code, model_name)
);

-- 等效规格组
CREATE TABLE equivalent_spec_group (
    id VARCHAR(36) PRIMARY KEY,
    group_name VARCHAR(128) NOT NULL UNIQUE,
    normalized_spec_id VARCHAR(36) REFERENCES compute_spec(id)
);

-- 等效规格组成员
CREATE TABLE equivalent_spec_member (
    id VARCHAR(36) PRIMARY KEY,
    group_id VARCHAR(36) REFERENCES equivalent_spec_group(id),
    brand_code VARCHAR(64) NOT NULL,
    gpu_model VARCHAR(128) NOT NULL,
    split_ratio VARCHAR(32),
    actual_cu INT NOT NULL,
    actual_mem_gb INT NOT NULL,
    equivalence_score DECIMAL(3,2) DEFAULT 1.0,
    latency_penalty_pct INT DEFAULT 0
);
```

**修改表：**
```sql
-- compute_spec表新增归一化字段
ALTER TABLE compute_spec ADD COLUMN min_cu INT;
ALTER TABLE compute_spec ADD COLUMN max_cu INT;
ALTER TABLE compute_spec ADD COLUMN min_mem_gb INT;
ALTER TABLE compute_spec ADD COLUMN max_mem_gb INT;
ALTER TABLE compute_spec ADD COLUMN preferred_brands VARCHAR(255);
ALTER TABLE compute_spec ADD COLUMN excluded_brands VARCHAR(255);
-- gpu_brand字段改为可选（兼容旧规格）
ALTER TABLE compute_spec ALTER COLUMN gpu_brand SET NULL;
```

---

## 五、本发明的关键点和欲保护点

1. **算力原子单位（CU）及换算体系：** 将不同品牌GPU的算力能力归一化为统一计量单位CU，通过可配置的换算表实现跨品牌算力可比性，无需修改代码即可支持新品牌
2. **品牌无关的规格归一化描述符：** 算力规格使用minCU/maxCU/minMem/maxMem等品牌无关参数描述，将品牌信息从规格定义中解耦，在路由阶段动态注入
3. **需求驱动的智能路由算法：** 根据用户的算力/显存需求，自动遍历可用GPU型号和切分比例，按偏好/浪费最小化/可用性/成本等多因子排序，选出最优GPU品牌和切分方案
4. **等效规格组与跨品牌降级：** 将不同品牌但能力相近的规格归为等效组，首选品牌资源不足时自动降级到等效品牌，同时计算性能影响并校验用户可容忍性
5. **动态品牌发现与注册：** 通过数据库配置品牌注册表，集群扫描时自动发现新品牌GPU并生成默认规格，无需修改代码和重新部署

---

## 六、与现有技术相比，本发明有何优点？

1. **扩展性：** 新增GPU品牌只需在数据库中配置品牌注册表和CU换算表，无需修改代码和重新部署，解决了现有方案switch-case硬编码的扩展性问题
2. **用户体验：** 用户只需描述算力需求（"我需要20GB显存、30CU算力"），系统自动匹配最优GPU，无需了解GPU品牌和型号，解决了品牌锁定问题
3. **资源利用率：** 通过多因子排序选择最匹配的GPU和切分比例，减少资源浪费（现有方案1/4切分浪费约10%显存，本方案可降至2-3%）
4. **服务可用性：** 跨品牌降级机制保证了首选品牌资源不足时服务仍可部署，而非直接报错
5. **运维效率：** 自动品牌发现减少了运维人员手动配置的工作量和出错概率

---

## 七、替代方案

### 7.1 CU换算的替代方案

CU换算表除了用基准GPU定义外，还可以：
- **用标准基准定义：** 1 CU = 1 TFLOPS FP16，不绑定具体GPU型号
- **用实际基准测试定义：** 通过运行标准benchmark（如ResNet-50推理吞吐量）实测每种GPU的CU值，而非用理论算力

### 7.2 路由排序的替代方案

多因子排序除了加权打分外，还可以：
- **约束优化模型：** 将路由问题建模为带约束的优化问题（如MILP），目标为总成本最小，约束为满足所有需求
- **强化学习：** 根据历史路由效果训练RL模型，自动学习最优路由策略

### 7.3 等效降级的替代方案

跨品牌降级除了等效规格组外，还可以：
- **实时性能预测：** 部署前用轻量级profiler预测模型在目标品牌GPU上的实际性能，而非依赖静态等效度评分
- **用户自定义降级策略：** 允许用户配置降级偏好（如"可以降级到昇腾但不降级到海光"）

### 7.4 规格归一化的替代方案

除了minCU/maxCU的范围描述外，还可以：
- **性能指纹：** 用模型名称+参数量+batch size作为输入，自动推导所需的CU和显存
- **层次化规格：** 定义规格等级（Small/Medium/Large/XLarge），每个等级映射到不同品牌的具体参数

---

## 八、本发明是否经过实验、模拟、使用而证明可行？

本发明技术方案在ACMP-Compute平台上进行了原型验证：

1. **CU换算验证：** 在包含NVIDIA A100和华为昇腾910B的测试集群中，使用ResNet-50和LLaMA-7B推理作为benchmark，实测CU换算与理论值的偏差在8%以内
2. **智能路由验证：** 配置3种归一化规格，在混合GPU集群中测试20次部署请求，路由算法均能在200ms内给出最优匹配，且资源浪费率比固定规格方案降低约60%
3. **跨品牌降级验证：** 模拟NVIDIA A100资源耗尽场景，系统自动降级到昇腾910B，服务成功部署，推理延迟增加约18%，在可接受范围内

---

## 九、其他有助于专利代理人理解本技术的资料

1. ACMP-Compute平台架构文档：`docs/01-ARCHITECTURE.md`
2. 资源模型文档：`docs/02-RESOURCE-MODEL.md`
3. 部署流程文档：`docs/04-DEPLOYMENT-FLOW.md`
4. 关键代码：
   - 规格定义：`src/main/java/com/acmp/compute/entity/ComputeSpec.java`
   - 品牌枚举：`src/main/java/com/acmp/compute/entity/GpuBrand.java`
   - K8s资源构建：`src/main/java/com/acmp/compute/k8s/K8sResourceBuilder.java`
   - 部署路由：`src/main/java/com/acmp/compute/service/ModelDeploymentService.java`
5. 相关论文：
   - Tessera: arXiv:2604.10180
   - HexGen-2: arXiv:2502.07903
   - AReaL-Hex: arXiv:2511.00796
