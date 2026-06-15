#!/usr/bin/env bash
# 给 kind 节点打模拟 HAMi 切分注解
# 真实环境是 HAMi device plugin 自动加的；测试环境我们手工模拟
#
# 用法：bash scripts/seed-hami-annotations.sh [node-name]
set -euo pipefail

NODE_NAME="${1:-}"

if [[ -z "$NODE_NAME" ]]; then
  NODE_NAME=$(kubectl get nodes -o jsonpath='{.items[0].metadata.name}')
  echo "▶ 未指定节点，使用第一个节点：$NODE_NAME"
fi

echo "▶ 给节点 $NODE_NAME 打 HAMi 切分注解"

# nvidia.com/gpu-memory: 整卡显存 MiB（A100-80GB = 81920）
kubectl annotate --overwrite node "$NODE_NAME" \
  nvidia.com/gpu-memory=81920

# nvidia.com/gpu.product: 真实环境 device plugin 也会加；这里给个样例
kubectl annotate --overwrite node "$NODE_NAME" \
  nvidia.com/gpu.product=NVIDIA-A100-SXM4-80GB \
  nvidia.com/gpu.family=a100

# virtualization-group-* 注解（HAMi 切分规格）
# 格式："显存MiB,算力%"
# 1/2 卡 → 40960,50
# 1/4 卡 → 20480,25
# 1/8 卡 → 10240,12
kubectl annotate --overwrite node "$NODE_NAME" \
  nvidia.com/virtualization-group-7b=6000,16 \
  nvidia.com/virtualization-group-14b=12000,33 \
  nvidia.com/virtualization-group-28b=24000,50 \
  nvidia.com/virtualization-group-40b=48000,66 \
  nvidia.com/virtualization-group-80b=81920,100

echo "✅ 注解已打："
kubectl get node "$NODE_NAME" -o jsonpath='{.metadata.annotations}' | tr ',' '\n' | grep -E 'nvidia\.com|gpu-memory' | sort
