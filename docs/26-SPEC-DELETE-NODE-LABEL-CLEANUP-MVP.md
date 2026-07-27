# 26. 删除算力规格时同步清理 Kubernetes Node 标签

## 目标

当管理员删除一个算力规格时，平台需要同时完成两类回收：

1. 删除数据库中的规格记录；
2. 清理所有受该规格影响的 Kubernetes Node 调度标签，避免残留脏状态影响后续入池和调度。

## 为什么需要这一步

删除算力规格后，如果 Node 上仍保留旧标签，后续可能出现以下问题：

- 节点被错误地继续识别为旧规格节点；
- 新规格入池时和旧标签冲突；
- 同一台机器后续重新加入其他资源池时，平台侧和 K8s 侧状态不一致。

因此，删除规格不应只做数据库回收，还要把 K8s 调度标签一起清掉。

## 当前行为

当前 `ComputeSpecService.delete()` 只做了以下事情：

- 检查规格是否仍被租户配额引用；
- 检查规格是否仍被推理服务引用；
- 清理数据库中的 Node / GPU 入池归属；
- 删除规格记录。

当前没有在删除规格时主动调用 K8s Node 标签清理逻辑。

## 期望行为

删除算力规格时，流程应为：

1. 校验规格是否存在；
2. 校验是否被租户配额使用；
3. 校验是否被推理服务使用；
4. 找出所有绑定该规格的 Node / GPU；
5. 清理这些 Node 上的 ACMP 调度标签；
6. 清理数据库里的 Node / GPU 归属；
7. 删除规格记录；
8. 打印完整操作日志，便于离线环境排查。

## 约束

- 如果规格仍在被配额或推理服务使用，禁止删除；
- 标签清理失败时需要明确记录失败原因；
- 不能只删数据库而不清 K8s 标签；
- 清理动作应尽量和已有的“集群重置”标签清理逻辑保持一致，避免两套规则并存。

## 后续改造待办

- [ ] 在 `ComputeSpecService.delete()` 中补充 K8s 标签清理；
- [ ] 按规格找到关联的 Node / GPU；
- [ ] 调用 `KubernetesClientManager` 的标签清理能力；
- [ ] 将标签清理和数据库删除放在同一删除链路中；
- [ ] 补充删除成功/失败的日志；
- [ ] 如有必要，补充单测或集成验证说明。

## 相关实现位置

- `src/main/java/com/acmp/compute/service/ComputeSpecService.java`
- `src/main/java/com/acmp/compute/k8s/KubernetesClientManager.java`
- `src/main/java/com/acmp/compute/k8s/KubernetesSchedulingLabels.java`
- `src/main/java/com/acmp/compute/mapper/ClusterNodeMapper.java`
- `src/main/java/com/acmp/compute/mapper/GpuDeviceMapper.java`

