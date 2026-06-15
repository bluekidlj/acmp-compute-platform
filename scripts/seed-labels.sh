#!/usr/bin/env bash
# 给 kind 节点打 7 条预置规格对应的 label
# 对应 schema-h2.sql 中 compute_spec 的 nodeSelector 值
#
# 用法：bash scripts/seed-labels.sh [node-name]
#   不传 node-name 时取 control-plane 节点
set -euo pipefail

NODE_NAME="${1:-}"

if [[ -z "$NODE_NAME" ]]; then
  NODE_NAME=$(kubectl get nodes -o jsonpath='{.items[0].metadata.name}')
  echo "▶ 未指定节点，使用第一个节点：$NODE_NAME"
fi

echo "▶ 给节点 $NODE_NAME 打 ACMP 1.0 规格 label"

# 独占整卡规格
kubectl label --overwrite node "$NODE_NAME" \
  pool=exclusive-nvidia-a100-80g \
  pool=exclusive-nvidia-h100-80g \
  pool=exclusive-hygon-dcu

# 共享（HAMi 切分）规格
kubectl label --overwrite node "$NODE_NAME" \
  pool=shared-hami-a100-1/2 \
  pool=shared-hami-a100-1/4 \
  pool=shared-hami-a100-1/8

# 超分规格
kubectl label --overwrite node "$NODE_NAME" \
  pool=oversell-a100-mig-1/2

echo "✅ label 已打："
kubectl get node "$NODE_NAME" --show-labels | tr ',' '\n' | grep pool || true
