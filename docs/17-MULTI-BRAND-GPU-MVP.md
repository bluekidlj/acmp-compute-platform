# 多品牌 GPU 支持 MVP 设计

## 1. 目标与边界

本次改造只完成管理员单人操作所需的完整链路，不引入复杂事务、并发调度、插件框架或自动纠错机制。

- 一个 Kubernetes Node 只安装一种品牌的 GPU。
- 一个集群允许同时存在英伟达、海光、华为昇腾节点。
- 平台保存设备和规格的品牌，并在集群清单、资源池、规格和租户配额页面提供品牌展示或筛选。
- 推理工作负载仍由现有 Deployment/Service 构建链路提交，只按品牌替换 HAMi 资源键。
- 首期支持 `NVIDIA`、`HYGON`、`HUAWEI_ASCEND` 三个枚举值。新增品牌时再增加枚举和一条资源键映射，不提前建设供应商插件体系。

## 2. 最小数据模型

品牌贯穿以下三个对象：

| 对象 | 字段 | 来源/作用 |
| --- | --- | --- |
| `gpu_device` | `gpu_brand` | 同步 Node allocatable 时识别 |
| `compute_spec` | `gpu_brand` | GPU 入池时从物理设备复制 |
| `tenant_spec_quota` 返回值 | `gpuBrand` | 从关联规格读取，无需增加冗余列 |

共享规格额外保存物理卡显存 `gpu_memory_mb`，用于把海光/昇腾的显存百分比换算成 HAMi 请求所需的 MiB 数值。

不单独创建品牌表。品牌目前是稳定、数量很少的系统能力，用枚举更符合 MVP。

## 3. 识别规则

集群同步读取 Node `status.allocatable`：

| 品牌 | 识别资源键 |
| --- | --- |
| 英伟达 | `nvidia.com/gpu` |
| 海光 | 优先 `hygon.com/dcunum`，兼容 `hygon.com/dcu`、`amd.com/dcu`、`amd.com/gpu` |
| 华为昇腾 | `huawei.com/Ascend*`，排除 `-memory` 和 `-core` 辅助资源 |

型号、显存、驱动版本继续优先读取节点标签或注解。一个节点若意外暴露多个品牌资源，当前实现记录警告并按英伟达、海光、昇腾的固定顺序取一个；管理员应修正节点设备插件配置，而不是由平台猜测如何混用。

升级已有环境后需要执行一次“集群管理 → 立即同步”，为存量 `gpu_device` 回填品牌。

## 4. 管理流程

1. 管理员同步集群，平台识别每张卡的品牌。
2. 在集群 GPU 清单中按全部、英伟达、海光、华为昇腾筛选。
3. 加入共享池或独享池时先选择品牌，再选择同品牌设备。
4. 入池生成的规格继承设备品牌；资源池和规格页面可按品牌筛选。
5. 给租户分配配额时先选择品牌，再选择该品牌的规格。
6. 项目部署推理服务时继续选择租户已有配额的规格；后端根据规格品牌生成对应资源请求。

品牌由设备识别结果向下传递，管理员无需在多个环节重复填写，从而避免设备品牌与规格品牌不一致。

## 5. Kubernetes/HAMi 资源映射

### 独享整卡

| 品牌 | 资源请求 |
| --- | --- |
| 英伟达 | `nvidia.com/gpu: 1` |
| 海光 | `hygon.com/dcunum: 1` |
| 华为昇腾 | `huawei.com/<具体型号>: 1`，例如 `huawei.com/Ascend910B: 1` |

### 共享卡

| 品牌 | 资源请求/注解 |
| --- | --- |
| 英伟达 | `nvidia.com/gpu: 1`、`nvidia.com/gpumem-percentage`、`nvidia.com/gpucores` |
| 海光 | `hygon.com/dcucores`、`hygon.com/dcumem` |
| 华为昇腾 | `huawei.com/<型号>: 1`、`<型号>-core`、`<型号>-memory`；Pod 注解 `huawei.com/vnpu-mode: hami-core` |

华为资源键依赖具体型号，因此昇腾设备加入资源池前必须能识别型号。共享卡还必须识别物理显存；缺少这些信息时平台拒绝入池并提示先修正节点标签/设备插件后重新同步。

HAMi 的设备支持和资源键以官方文档为准：

- [HAMi 支持的设备](https://project-hami.io/docs/v2.5.0/userguide/device-supported)
- [海光 DCU 共享配置](https://project-hami.io/docs/v2.4.1/userguide/hygon-device/enable-hygon-dcu-sharing)
- [华为昇腾共享配置](https://project-hami.io/docs/userguide/ascend-device/enable-ascend-sharing)

## 6. 当前限制

- 不支持同一 Node 混装多个 GPU 品牌。
- 不自动安装或配置各厂商驱动、Device Plugin、HAMi scheduler。
- 不做跨品牌规格折算；租户配额按具体品牌规格授予。
- 不保证同一推理镜像兼容所有品牌。英伟达、海光、昇腾通常需要各自可运行的镜像，镜像选择仍由部署配置负责。
- 型号标签和资源键必须与集群实际安装的 HAMi/厂商插件版本一致，上线前应分别用一台对应品牌节点做最小 Pod 验证。

