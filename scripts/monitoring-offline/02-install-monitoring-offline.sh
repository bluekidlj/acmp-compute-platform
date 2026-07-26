#!/usr/bin/env bash
set -Eeuo pipefail

# 内网执行：导入镜像到 containerd，并在 Master 上安装监控组件。

MODE_LOAD=0
MODE_INSTALL=0
CONTAINERD_NAMESPACE="${CONTAINERD_NAMESPACE:-k8s.io}"
KUBE_PROM_STACK_VERSION="${KUBE_PROM_STACK_VERSION:-65.5.1}"
GPU_OPERATOR_VERSION="${GPU_OPERATOR_VERSION:-25.3.0}"

while [ "$#" -gt 0 ]; do
  case "$1" in
    --load-images) MODE_LOAD=1 ;;
    --install) MODE_INSTALL=1 ;;
    *) echo "未知参数: $1" >&2; exit 1 ;;
  esac
  shift
done

if [ "${MODE_LOAD}" -eq 0 ] && [ "${MODE_INSTALL}" -eq 0 ]; then
  echo "用法: sudo $0 --load-images | --install" >&2
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUNDLE_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

log() {
  printf '\n[%s] %s\n' "$(date '+%F %T')" "$*"
}

require_root() {
  [ "$(id -u)" -eq 0 ] || {
    echo "请使用 sudo 运行" >&2
    exit 1
  }
}

load_images() {
  require_root
  command -v ctr >/dev/null 2>&1 || {
    echo "缺少 ctr，请先安装 containerd" >&2
    exit 1
  }
  [ -f "${BUNDLE_DIR}/images/monitoring-images.tar" ] || {
    echo "未找到镜像包: ${BUNDLE_DIR}/images/monitoring-images.tar" >&2
    exit 1
  }
  log "导入镜像到 containerd namespace=${CONTAINERD_NAMESPACE}"
  ctr -n "${CONTAINERD_NAMESPACE}" images import "${BUNDLE_DIR}/images/monitoring-images.tar"
  log "镜像导入完成"
}

install_charts() {
  [ -f /etc/kubernetes/admin.conf ] || {
    echo "未找到 /etc/kubernetes/admin.conf，请在 Master 节点执行安装" >&2
    exit 1
  }
  command -v helm >/dev/null 2>&1 || {
    echo "缺少 helm，请先在 Master 安装 helm" >&2
    exit 1
  }
  command -v kubectl >/dev/null 2>&1 || {
    echo "缺少 kubectl" >&2
    exit 1
  }
  export KUBECONFIG=/etc/kubernetes/admin.conf
  KUBE_PROM_STACK_CHART="$(find "${BUNDLE_DIR}/charts" -maxdepth 1 -type f -name 'kube-prometheus-stack-*.tgz' | head -n 1)"
  GPU_OPERATOR_CHART="$(find "${BUNDLE_DIR}/charts" -maxdepth 1 -type f -name 'gpu-operator-*.tgz' | head -n 1)"
  if [ -z "${KUBE_PROM_STACK_CHART}" ] || [ -z "${GPU_OPERATOR_CHART}" ]; then
    echo "未找到 chart 文件，请确认离线包完整" >&2
    exit 1
  fi

  log "安装 kube-prometheus-stack"
  helm upgrade --install kube-prometheus-stack \
    "${KUBE_PROM_STACK_CHART}" \
    --namespace monitoring \
    --create-namespace \
    --values "${BUNDLE_DIR}/values/prometheus-values.yaml" \
    --wait \
    --timeout 10m

  log "安装 gpu-operator"
  helm upgrade --install gpu-operator \
    "${GPU_OPERATOR_CHART}" \
    --namespace gpu-operator \
    --create-namespace \
    --values "${BUNDLE_DIR}/values/gpu-operator-values.yaml" \
    --wait \
    --timeout 10m
}

if [ "${MODE_LOAD}" -eq 1 ]; then
  load_images
fi

if [ "${MODE_INSTALL}" -eq 1 ]; then
  install_charts
fi
