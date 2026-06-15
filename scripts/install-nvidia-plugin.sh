#!/usr/bin/env bash
# 安装 NVIDIA device plugin（模拟模式）到 kind 集群
#
# 真实 GPU 集群：直接 `kubectl apply -f https://raw.githubusercontent.com/NVIDIA/k8s-device-plugin/.../nvidia-device-plugin.yml`
# 测试环境（无 GPU）：使用 device plugin 的 "mock" 模式不可行
# 我们的方案：用自定义的 "fake" device plugin DaemonSet（pod 提交后 allocatable 出现 nvidia.com/gpu=1）
#
# 简化方案：直接用 kubectl patch node status.allocatable 加 nvidia.com/gpu
# 这样 platform 的 /capacity /scan /gpus 接口都能跑通

set -euo pipefail

NODE_NAME="${1:-}"
if [[ -z "$NODE_NAME" ]]; then
  NODE_NAME=$(kubectl get nodes -o jsonpath='{.items[0].metadata.name}')
fi

echo "▶ 给节点 $NODE_NAME 注入 nvidia.com/gpu allocatable（测试用）"

# 真实生产是 device plugin 通过 PATCH status.allocatable 上报
# 这里直接 patch node 状态，模拟 device plugin 的行为
kubectl patch node "$NODE_NAME" --subresource=status --type=json \
  -p='[{"op": "add", "path": "/status/allocatable/nvidia.com~1gpu", "value": "1"}]' \
  || echo "  ↳ 字段可能已存在"

# CPU / Memory
kubectl patch node "$NODE_NAME" --subresource=status --type=json \
  -p='[{"op": "add", "path": "/status/allocatable/cpu", "value": "16"}]' \
  || echo "  ↳ cpu 已存在"

kubectl patch node "$NODE_NAME" --subresource=status --type=json \
  -p='[{"op": "add", "path": "/status/allocatable/memory", "value": "64Gi"}]' \
  || echo "  ↳ memory 已存在"

echo "▶ 节点 allocatable 现状："
kubectl get node "$NODE_NAME" -o jsonpath='{.status.allocatable}' | tr ',' '\n'

echo ""
echo "✅ 若要切回真实环境，从集群中删除该 patch 即可（或新建集群）。"
