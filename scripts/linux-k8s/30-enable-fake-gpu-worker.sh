#!/usr/bin/env bash
set -Eeuo pipefail

# 在 Master 节点执行，为一个真实 Worker Node 模拟 Tesla V100。
# Fake GPU 是集群级 Operator，不能只靠 Worker 的 kubelet 凭据安装。

WORKER_NODE="${1:-}"
GPU_COUNT="${GPU_COUNT:-2}"
GPU_POOL="${GPU_POOL:-v100}"
GPU_PRODUCT="${GPU_PRODUCT:-NVIDIA-TESLA-V100-SXM2-16GB}"
GPU_MEMORY_MIB="${GPU_MEMORY_MIB:-16384}"
HELM_VERSION="${HELM_VERSION:-v3.16.4}"
KWOK_VERSION="${KWOK_VERSION:-v0.7.0}"
GITHUB_PROXY="${GITHUB_PROXY:-}"

[[ "${EUID}" -eq 0 ]] || { echo "请使用 sudo 运行"; exit 1; }
[[ -n "${WORKER_NODE}" ]] || {
  echo "用法: sudo $0 <worker-node-name>" >&2
  exit 1
}
[[ -f /etc/kubernetes/admin.conf ]] || {
  echo "未找到 /etc/kubernetes/admin.conf，请在 Master 节点执行" >&2
  exit 1
}
export KUBECONFIG=/etc/kubernetes/admin.conf

kubectl get node "${WORKER_NODE}" >/dev/null
if kubectl get node "${WORKER_NODE}" -o json \
  | jq -e '.metadata.labels | has("node-role.kubernetes.io/control-plane") or has("node-role.kubernetes.io/master")' \
  >/dev/null; then
  echo "拒绝给 control-plane 节点模拟 GPU: ${WORKER_NODE}" >&2
  exit 1
fi

if ! command -v helm >/dev/null 2>&1; then
  echo "安装 Helm ${HELM_VERSION}"
  curl -fL --retry 5 --retry-delay 3 \
    "https://get.helm.sh/helm-${HELM_VERSION}-linux-amd64.tar.gz" \
    -o /tmp/helm.tar.gz
  tar -C /tmp -xzf /tmp/helm.tar.gz
  install -m 0755 /tmp/linux-amd64/helm /usr/local/bin/helm
fi

echo "安装 KWOK ${KWOK_VERSION}"
KWOK_MANIFEST="/tmp/kwok-${KWOK_VERSION}.yaml"
curl -fL --retry 5 --retry-delay 3 \
  "${GITHUB_PROXY}https://github.com/kubernetes-sigs/kwok/releases/download/${KWOK_VERSION}/kwok.yaml" \
  -o "${KWOK_MANIFEST}"
# 这个 demo 只需要 kwok-controller，不需要 APF 配置对象。
# 某些环境下 FlowSchema / PriorityLevelConfiguration 的资源映射会失败，所以先删掉再应用。
python3 - "${KWOK_MANIFEST}" <<'PY' >/tmp/kwok-stripped.yaml
import sys
from pathlib import Path

source = Path(sys.argv[1]).read_text(encoding="utf-8")
documents = source.split("\n---\n")
kept = []
for doc in documents:
    text = doc.strip()
    if not text:
        continue
    if "kind: FlowSchema" in text or "kind: PriorityLevelConfiguration" in text:
        continue
    kept.append(doc.strip("\n"))

sys.stdout.write("\n---\n".join(kept))
sys.stdout.write("\n")
PY
kubectl apply -f /tmp/kwok-stripped.yaml
kubectl rollout status deployment/kwok-controller -n kube-system --timeout=180s

echo "安装 Fake GPU Operator"
helm repo add fake-gpu-operator \
  https://runai.jfrog.io/artifactory/api/helm/fake-gpu-operator-charts-prod \
  --force-update
helm repo update
helm upgrade --install gpu-operator \
  fake-gpu-operator/fake-gpu-operator \
  --namespace gpu-operator \
  --create-namespace \
  --set "topology.nodePools.${GPU_POOL}.gpuCount=${GPU_COUNT}" \
  --set "topology.nodePools.${GPU_POOL}.gpuProduct=${GPU_PRODUCT}" \
  --set "topology.nodePools.${GPU_POOL}.gpuMemory=${GPU_MEMORY_MIB}"

kubectl label node "${WORKER_NODE}" \
  "run.ai/simulated-gpu-node-pool=${GPU_POOL}" \
  "nvidia.com/gpu.product=${GPU_PRODUCT}" \
  "nvidia.com/gpu.family=volta" \
  "nvidia.com/cuda.driver-version.full=535.183.01" \
  "nvidia.com/cuda.runtime-version.full=12.2" \
  --overwrite
kubectl annotate node "${WORKER_NODE}" \
  "nvidia.com/gpu-memory=${GPU_MEMORY_MIB}" \
  --overwrite

echo "等待模拟 GPU 上报"
for _ in $(seq 1 60); do
  current="$(kubectl get node "${WORKER_NODE}" \
    -o jsonpath='{.status.allocatable.nvidia\.com/gpu}' 2>/dev/null || true)"
  if [[ "${current}" == "${GPU_COUNT}" ]]; then
    break
  fi
  sleep 3
done

current="$(kubectl get node "${WORKER_NODE}" \
  -o jsonpath='{.status.allocatable.nvidia\.com/gpu}' 2>/dev/null || true)"
[[ "${current}" == "${GPU_COUNT}" ]] || {
  kubectl get pods -n gpu-operator -o wide
  echo "模拟 GPU 未按预期上报，期望 ${GPU_COUNT}，实际 ${current:-空}" >&2
  exit 1
}

echo "Fake GPU 配置完成"
kubectl get nodes \
  -o custom-columns='NAME:.metadata.name,IP:.status.addresses[0].address,GPU:.status.allocatable.nvidia\.com/gpu,MODEL:.metadata.labels.nvidia\.com/gpu\.product'
echo
echo "现在回到 ACMP 集群管理点击“立即同步”。Fake GPU 只能验证调度，不能运行 CUDA/vLLM。"
