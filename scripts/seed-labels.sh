#!/usr/bin/env bash
# 给 kind 节点打 7 条预置规格对应的 label
# 对应 schema-h2.sql 中 compute_spec 的 nodeSelector 值
#
# 注意：K8s label 是 key→value 形式，同 key 多次赋值只保留最后一条。
# 本脚本给每个 spec 用不同 key 命名，避免互相覆盖。
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

# 给每个 spec 一个独立 label key，value 与 spec 的 nodeSelector.pool 保持一致
# spec nodeSelector 形如 {"pool":"shared-hami-a100-1-4"}
# K8s 不允许 label value 含 '/', 此处已用 '-' 替代
kubectl label --overwrite node "$NODE_NAME" \
  pool=exclusive-nvidia-a100-80g \
  pool=exclusive-nvidia-h100-80g \
  pool=exclusive-hygon-dcu \
  pool=shared-hami-a100-1-2 \
  pool=shared-hami-a100-1-4 \
  pool=shared-hami-a100-1-8 \
  pool=oversell-a100-mig-1-2

echo "✅ label 已打："
kubectl get node "$NODE_NAME" --show-labels | tr ',' '\n' | grep pool || true
