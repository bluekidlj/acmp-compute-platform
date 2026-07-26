#!/usr/bin/env bash
set -Eeuo pipefail

# 外网执行：下载监控 Helm Chart，按最终 values 渲染镜像清单，导出 containerd 可导入的镜像包。

BUNDLE_DIR="${BUNDLE_DIR:-acmp-monitoring-offline-bundle}"
KUBE_PROM_STACK_VERSION="${KUBE_PROM_STACK_VERSION:-65.5.1}"
GPU_OPERATOR_VERSION="${GPU_OPERATOR_VERSION:-25.3.0}"
HELM_BIN="${HELM_BIN:-helm}"
IMAGE_TOOL="${IMAGE_TOOL:-docker}"
IMAGE_PULL_RETRY="${IMAGE_PULL_RETRY:-5}"
IMAGE_PULL_SLEEP_SECONDS="${IMAGE_PULL_SLEEP_SECONDS:-10}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="${ROOT_DIR:-${SCRIPT_DIR}}"
WORK_DIR="${ROOT_DIR}/${BUNDLE_DIR}"

log() {
  printf '\n[%s] %s\n' "$(date '+%F %T')" "$*"
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "缺少命令: $1" >&2
    exit 1
  }
}

extract_images() {
  grep -hE '^[[:space:]]*image:[[:space:]]*"?[^"]+' "${WORK_DIR}"/rendered/*.yaml \
    | sed -E 's/^[[:space:]]*image:[[:space:]]*"?([^"]+)"?[[:space:]]*$/\1/' \
    | sed '/^$/d' \
    | grep -E '(/|@sha256:)' \
    | sort -u
}

prepare_values() {
  if [ -f "${SCRIPT_DIR}/values/prometheus-values.yaml" ]; then
    cp "${SCRIPT_DIR}/values/prometheus-values.yaml" "${WORK_DIR}/values/prometheus-values.yaml"
  else
    log "未发现 values/prometheus-values.yaml，生成默认 Prometheus values"
    cat > "${WORK_DIR}/values/prometheus-values.yaml" <<'EOF'
prometheus:
  service:
    type: NodePort
    nodePort: 30090
  prometheusSpec:
    retention: 15d
    scrapeInterval: 30s
    evaluationInterval: 30s
    serviceMonitorNamespaceSelector: {}
    podMonitorNamespaceSelector: {}
    serviceMonitorSelector: {}
    serviceMonitorSelectorNilUsesHelmValues: false
    podMonitorSelector: {}
    podMonitorSelectorNilUsesHelmValues: false

grafana:
  enabled: true
  persistence:
    enabled: false

alertmanager:
  enabled: true

kube-state-metrics:
  enabled: true

prometheus-node-exporter:
  enabled: true
EOF
  fi

  if [ -f "${SCRIPT_DIR}/values/gpu-operator-values.yaml" ]; then
    cp "${SCRIPT_DIR}/values/gpu-operator-values.yaml" "${WORK_DIR}/values/gpu-operator-values.yaml"
  else
    log "未发现 values/gpu-operator-values.yaml，生成默认 GPU Operator values"
    cat > "${WORK_DIR}/values/gpu-operator-values.yaml" <<'EOF'
driver:
  enabled: false

toolkit:
  enabled: false

dcgmExporter:
  enabled: true
  serviceMonitor:
    enabled: true
    interval: 30s
  enablePodLabels: true
EOF
  fi
}

copy_install_scripts() {
  if [ -f "${SCRIPT_DIR}/02-install-monitoring-offline.sh" ]; then
    cp "${SCRIPT_DIR}/02-install-monitoring-offline.sh" "${WORK_DIR}/scripts/02-install-monitoring-offline.sh"
  else
    echo "缺少 02-install-monitoring-offline.sh，请和下载脚本放在同一目录" >&2
    exit 1
  fi
  if [ -f "${SCRIPT_DIR}/03-verify-monitoring.sh" ]; then
    cp "${SCRIPT_DIR}/03-verify-monitoring.sh" "${WORK_DIR}/scripts/03-verify-monitoring.sh"
  else
    echo "缺少 03-verify-monitoring.sh，请和下载脚本放在同一目录" >&2
    exit 1
  fi
}

find_chart() {
  pattern="$1"
  found="$(find "${WORK_DIR}/charts" -maxdepth 1 -type f -name "${pattern}" | head -n 1)"
  if [ -z "${found}" ]; then
    echo "未找到 chart: ${pattern}" >&2
    exit 1
  fi
  printf '%s' "${found}"
}

require_cmd "${HELM_BIN}"
require_cmd "${IMAGE_TOOL}"
require_cmd tar
require_cmd grep
require_cmd sed

rm -rf "${WORK_DIR}"
mkdir -p "${WORK_DIR}/charts" "${WORK_DIR}/images" "${WORK_DIR}/rendered" "${WORK_DIR}/values" "${WORK_DIR}/scripts"

prepare_values
copy_install_scripts

log "下载 Helm Chart"
"${HELM_BIN}" repo add prometheus-community https://prometheus-community.github.io/helm-charts --force-update
"${HELM_BIN}" repo add nvidia https://helm.ngc.nvidia.com/nvidia --force-update
"${HELM_BIN}" repo update
"${HELM_BIN}" pull prometheus-community/kube-prometheus-stack \
  --version "${KUBE_PROM_STACK_VERSION}" \
  --destination "${WORK_DIR}/charts"
"${HELM_BIN}" pull nvidia/gpu-operator \
  --version "${GPU_OPERATOR_VERSION}" \
  --destination "${WORK_DIR}/charts"

log "按最终 values 渲染 Chart"
KUBE_PROM_STACK_CHART="$(find_chart 'kube-prometheus-stack-*.tgz')"
GPU_OPERATOR_CHART="$(find_chart 'gpu-operator-*.tgz')"
"${HELM_BIN}" template kube-prometheus-stack \
  "${KUBE_PROM_STACK_CHART}" \
  --namespace monitoring \
  --values "${WORK_DIR}/values/prometheus-values.yaml" \
  > "${WORK_DIR}/rendered/kube-prometheus-stack.yaml"
"${HELM_BIN}" template gpu-operator \
  "${GPU_OPERATOR_CHART}" \
  --namespace gpu-operator \
  --create-namespace \
  --values "${WORK_DIR}/values/gpu-operator-values.yaml" \
  > "${WORK_DIR}/rendered/gpu-operator.yaml"

extract_images > "${WORK_DIR}/images/images.txt"
if [ ! -s "${WORK_DIR}/images/images.txt" ]; then
  echo "未从渲染结果中提取到镜像，请检查 Chart 和 values" >&2
  exit 1
fi

log "拉取镜像"
while IFS= read -r image; do
  echo "pull ${image}"
  attempt=1
  while true; do
    if DOCKER_CLIENT_TIMEOUT=300 COMPOSE_HTTP_TIMEOUT=300 "${IMAGE_TOOL}" pull "${image}"; then
      break
    fi
    if [ "${attempt}" -ge "${IMAGE_PULL_RETRY}" ]; then
      echo "镜像拉取失败: ${image}" >&2
      exit 1
    fi
    echo "第 ${attempt} 次拉取失败，${IMAGE_PULL_SLEEP_SECONDS} 秒后重试: ${image}" >&2
    attempt=$((attempt + 1))
    sleep "${IMAGE_PULL_SLEEP_SECONDS}"
  done
done < "${WORK_DIR}/images/images.txt"

log "保存镜像包"
"${IMAGE_TOOL}" save -o "${WORK_DIR}/images/monitoring-images.tar" \
  $(tr '\n' ' ' < "${WORK_DIR}/images/images.txt")

log "记录镜像 digest"
while IFS= read -r image; do
  "${IMAGE_TOOL}" image inspect --format='{{index .RepoDigests 0}}' "${image}" 2>/dev/null || echo "${image}"
done < "${WORK_DIR}/images/images.txt" > "${WORK_DIR}/images/images.lock"

cat > "${WORK_DIR}/README.md" <<EOF
# ACMP Monitoring Offline Bundle

Chart versions:
- kube-prometheus-stack: ${KUBE_PROM_STACK_VERSION}
- gpu-operator: ${GPU_OPERATOR_VERSION}

内网安装：

\`\`\`bash
tar -xzf ${BUNDLE_DIR}.tar.gz
cd ${BUNDLE_DIR}
sudo ./scripts/02-install-monitoring-offline.sh --load-images
sudo ./scripts/02-install-monitoring-offline.sh --install
./scripts/03-verify-monitoring.sh
\`\`\`

ACMP 后端在集群外时配置：

\`\`\`text
PROMETHEUS_URL=http://<任一K8s节点内网IP>:30090
\`\`\`
EOF

log "打包离线目录"
tar -C "${ROOT_DIR}" -czf "${ROOT_DIR}/${BUNDLE_DIR}.tar.gz" "${BUNDLE_DIR}"

log "完成: ${ROOT_DIR}/${BUNDLE_DIR}.tar.gz"
