#!/usr/bin/env bash
set -Eeuo pipefail

# 内网执行：导入镜像到 containerd，并在 Master 上安装监控组件。

MODE_LOAD=0
MODE_INSTALL=0
MODE_INSTALL_STACK=0
MODE_INSTALL_GPU_OPERATOR=0
MODE_VERIFY_IMAGES=0
CONTAINERD_NAMESPACE="${CONTAINERD_NAMESPACE:-k8s.io}"
KUBE_PROM_STACK_VERSION="${KUBE_PROM_STACK_VERSION:-65.5.1}"
GPU_OPERATOR_VERSION="${GPU_OPERATOR_VERSION:-25.3.0}"

while [ "$#" -gt 0 ]; do
  case "$1" in
    --load-images) MODE_LOAD=1 ;;
    --install) MODE_INSTALL=1 ;;
    --install-stack) MODE_INSTALL_STACK=1 ;;
    --install-gpu-operator) MODE_INSTALL_GPU_OPERATOR=1 ;;
    --verify-images) MODE_VERIFY_IMAGES=1 ;;
    *) echo "未知参数: $1" >&2; exit 1 ;;
  esac
  shift
done

if [ "${MODE_LOAD}" -eq 0 ] && [ "${MODE_INSTALL}" -eq 0 ] && [ "${MODE_INSTALL_STACK}" -eq 0 ] && [ "${MODE_INSTALL_GPU_OPERATOR}" -eq 0 ] && [ "${MODE_VERIFY_IMAGES}" -eq 0 ]; then
  echo "用法: sudo $0 --load-images | --install | --install-stack | --install-gpu-operator | --verify-images" >&2
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUNDLE_DIR=""
for candidate in \
  "${PWD}/acmp-monitoring-offline-bundle" \
  "${PWD}/acmp-monitoring-offline-bundle-cn" \
  "${SCRIPT_DIR}/acmp-monitoring-offline-bundle" \
  "${SCRIPT_DIR}/acmp-monitoring-offline-bundle-cn" \
  "${PWD}" \
  "${SCRIPT_DIR}" \
  "${SCRIPT_DIR}/.."
do
  if [ -d "${candidate}/images" ] && [ -d "${candidate}/charts" ] && [ -d "${candidate}/values" ]; then
    BUNDLE_DIR="$(cd "${candidate}" && pwd)"
    break
  fi
done

if [ -z "${BUNDLE_DIR}" ]; then
  echo "未找到离线包目录，请确认当前目录或脚本目录下存在 images/charts/values" >&2
  exit 1
fi

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
  local imported_any=0

  # 允许把补充包（*.tar.gz）直接放在当前目录或脚本目录，避免手工解压。
  local supplement_tmp=""
  while IFS= read -r supplement; do
    [ -n "${supplement}" ] || continue
    [ -f "${supplement}" ] || continue
    [ -n "${supplement_tmp}" ] || supplement_tmp="$(mktemp -d)"
    log "解压补充镜像包: ${supplement}"
    tar -xzf "${supplement}" -C "${supplement_tmp}"
  done < <(find "${PWD}" "${SCRIPT_DIR}" -maxdepth 1 -type f \( -name 'gpu-missing-components*.tar.gz' -o -name 'missing-images*.tar.gz' \) 2>/dev/null | sort -u)

  if [ -n "${supplement_tmp}" ]; then
    while IFS= read -r archive; do
      [ -n "${archive}" ] || continue
      log "导入解压后的补充镜像包到 containerd namespace=${CONTAINERD_NAMESPACE}: ${archive}"
      ctr -n "${CONTAINERD_NAMESPACE}" images import "${archive}"
      imported_any=1
    done < <(find "${supplement_tmp}" -type f -name '*.tar' | sort)
    rm -rf "${supplement_tmp}"
  fi

  if [ -f "${BUNDLE_DIR}/images/monitoring-images.tar" ]; then
    log "导入单个镜像总包到 containerd namespace=${CONTAINERD_NAMESPACE}"
    ctr -n "${CONTAINERD_NAMESPACE}" images import "${BUNDLE_DIR}/images/monitoring-images.tar"
    imported_any=1
  fi

  while IFS= read -r archive; do
    [ -n "${archive}" ] || continue
    log "导入补充镜像包到 containerd namespace=${CONTAINERD_NAMESPACE}: ${archive}"
    ctr -n "${CONTAINERD_NAMESPACE}" images import "${archive}"
    imported_any=1
  done < <(find "${BUNDLE_DIR}/images" -maxdepth 1 -type f \( -name 'gpu-missing-components*.tar' -o -name 'missing-images*.tar' -o -name 'extra-*.tar' \) | sort)

  if [ "${imported_any}" -eq 1 ]; then
    log "镜像导入完成"
    return 0
  fi

  ARCHIVES_FILE="${BUNDLE_DIR}/images/archives.txt"
  [ -f "${ARCHIVES_FILE}" ] || {
    echo "未找到镜像包: ${BUNDLE_DIR}/images/monitoring-images.tar" >&2
    echo "也未找到逐镜像归档清单: ${ARCHIVES_FILE}" >&2
    exit 1
  }

  log "按镜像逐个导入到 containerd namespace=${CONTAINERD_NAMESPACE}"
  while IFS= read -r archive; do
    [ -n "${archive}" ] || continue
    [ -f "${archive}" ] || {
      echo "缺少镜像归档: ${archive}" >&2
      exit 1
    }
    log "import ${archive}"
    ctr -n "${CONTAINERD_NAMESPACE}" images import "${archive}"
  done < "${ARCHIVES_FILE}"
  log "镜像导入完成"
}

verify_images() {
  require_root
  command -v ctr >/dev/null 2>&1 || {
    echo "缺少 ctr，请先安装 containerd" >&2
    exit 1
  }

  local images_file="${BUNDLE_DIR}/images/images.txt"
  [ -f "${images_file}" ] || {
    echo "未找到镜像清单: ${images_file}" >&2
    exit 1
  }

  local missing=0
  log "检查 containerd namespace=${CONTAINERD_NAMESPACE} 中的关键镜像"
  while IFS= read -r image; do
    [ -n "${image}" ] || continue
    if ! ctr -n "${CONTAINERD_NAMESPACE}" images ls | awk 'NR>1 {print $1}' | grep -Fxq "${image}"; then
      echo "缺少镜像: ${image}" >&2
      missing=1
    fi
  done < "${images_file}"

  if [ "${missing}" -ne 0 ]; then
    echo "镜像完整性检查失败，请先执行 --load-images" >&2
    exit 1
  fi

  log "镜像完整性检查通过"
}

install_stack() {
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
}

install_gpu_operator() {
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
  GPU_OPERATOR_CHART="$(find "${BUNDLE_DIR}/charts" -maxdepth 1 -type f -name 'gpu-operator-*.tgz' | head -n 1)"
  if [ -z "${GPU_OPERATOR_CHART}" ]; then
    echo "未找到 gpu-operator chart 文件，请确认离线包完整" >&2
    exit 1
  fi

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

if [ "${MODE_VERIFY_IMAGES}" -eq 1 ]; then
  verify_images
fi

if [ "${MODE_INSTALL}" -eq 1 ]; then
  install_stack
  install_gpu_operator
fi

if [ "${MODE_INSTALL_STACK}" -eq 1 ]; then
  install_stack
fi

if [ "${MODE_INSTALL_GPU_OPERATOR}" -eq 1 ]; then
  install_gpu_operator
fi
