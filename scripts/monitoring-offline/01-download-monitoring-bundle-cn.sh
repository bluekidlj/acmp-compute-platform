#!/usr/bin/env bash
set -Eeuo pipefail

# 国内镜像优先版：
# 1. 优先从国内镜像站拉取能找到的镜像
# 2. 找不到时回退到原始镜像仓库
# 3. 生成的离线包结构与原版 01 脚本一致

BUNDLE_DIR="${BUNDLE_DIR:-acmp-monitoring-offline-bundle-cn}"
KUBE_PROM_STACK_VERSION="${KUBE_PROM_STACK_VERSION:-65.5.1}"
GPU_OPERATOR_VERSION="${GPU_OPERATOR_VERSION:-25.3.0}"
HELM_BIN="${HELM_BIN:-helm}"
IMAGE_TOOL="${IMAGE_TOOL:-docker}"
IMAGE_PULL_RETRY="${IMAGE_PULL_RETRY:-5}"
IMAGE_PULL_SLEEP_SECONDS="${IMAGE_PULL_SLEEP_SECONDS:-10}"
EXTRA_REQUIRED_IMAGES=(
  'nvcr.io/nvidia/cloud-native/gpu-operator-validator:v25.3.0'
  'quay.io/prometheus-operator/prometheus-config-reloader:v0.77.2'
)

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

add_required_images() {
  local image
  for image in "${EXTRA_REQUIRED_IMAGES[@]}"; do
    printf '%s\n' "${image}"
  done
}

prepare_values() {
  if [ -f "${SCRIPT_DIR}/values/prometheus-values.yaml" ]; then
    cp "${SCRIPT_DIR}/values/prometheus-values.yaml" "${WORK_DIR}/values/prometheus-values.yaml"
  else
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
  cp "${SCRIPT_DIR}/02-install-monitoring-offline.sh" "${WORK_DIR}/scripts/02-install-monitoring-offline.sh"
  cp "${SCRIPT_DIR}/03-verify-monitoring.sh" "${WORK_DIR}/scripts/03-verify-monitoring.sh"
}

find_chart() {
  pattern="$1"
  found="$(find "${WORK_DIR}/charts" -maxdepth 1 -type f -name "${pattern}" | head -n 1)"
  [ -n "${found}" ] || {
    echo "未找到 chart: ${pattern}" >&2
    exit 1
  }
  printf '%s' "${found}"
}

mirror_candidates() {
  case "$1" in
    docker.io/bats/bats:v1.4.1)
      printf '%s\n' \
        'swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/bats/bats:v1.4.1'
      ;;
    docker.io/grafana/grafana:11.2.2-security-01)
      printf '%s\n' \
        'swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/grafana/grafana:11.2.2-security-01'
      ;;
    quay.io/kiwigrid/k8s-sidecar:1.28.0)
      printf '%s\n' \
        'swr.cn-north-4.myhuaweicloud.com/ddn-k8s/quay.io/kiwigrid/k8s-sidecar:1.28.0'
      ;;
    quay.io/prometheus-operator/prometheus-operator:v0.77.2)
      printf '%s\n' \
        'swr.cn-north-4.myhuaweicloud.com/ddn-k8s/quay.io/prometheus-operator/prometheus-operator:v0.77.2'
      ;;
    quay.io/prometheus/alertmanager:v0.27.0)
      printf '%s\n' \
        'swr.cn-north-4.myhuaweicloud.com/ddn-k8s/quay.io/prometheus/alertmanager:v0.27.0'
      ;;
    quay.io/prometheus/node-exporter:v1.8.2)
      printf '%s\n' \
        'swr.cn-north-4.myhuaweicloud.com/ddn-k8s/quay.io/prometheus/node-exporter:v1.8.2'
      ;;
    quay.io/prometheus/prometheus:v2.55.0)
      printf '%s\n' \
        'swr.cn-north-4.myhuaweicloud.com/ddn-k8s/quay.io/prometheus/prometheus:v2.55.0'
      ;;
    registry.k8s.io/ingress-nginx/kube-webhook-certgen:v20221220-controller-v1.5.1-58-g787ea74b6)
      printf '%s\n' \
        'swr.cn-north-4.myhuaweicloud.com/ddn-k8s/registry.k8s.io/ingress-nginx/kube-webhook-certgen:v20221220-controller-v1.5.1-58-g787ea74b6'
      ;;
    registry.k8s.io/kube-state-metrics/kube-state-metrics:v2.13.0)
      printf '%s\n' \
        'swr.cn-north-4.myhuaweicloud.com/ddn-k8s/registry.k8s.io/kube-state-metrics/kube-state-metrics:v2.13.0'
      ;;
    registry.k8s.io/nfd/node-feature-discovery:v0.17.2)
      printf '%s\n' \
        'swr.cn-north-4.myhuaweicloud.com/ddn-k8s/registry.k8s.io/nfd/node-feature-discovery:v0.17.2'
      ;;
    nvcr.io/nvidia/gpu-operator:v25.3.0)
      printf '%s\n' \
        'swr.cn-north-4.myhuaweicloud.com/ddn-k8s/nvcr.io/nvidia/gpu-operator:v25.3.0'
      ;;
    *)
      printf '%s\n' "$1"
      ;;
  esac
}

pull_with_fallback() {
  local original="$1"
  local mirror
  while IFS= read -r mirror; do
    [ -n "${mirror}" ] || continue
    log "尝试国内镜像: ${mirror}"
    if DOCKER_CLIENT_TIMEOUT=300 COMPOSE_HTTP_TIMEOUT=300 "${IMAGE_TOOL}" pull "${mirror}"; then
      if [ "${mirror}" != "${original}" ]; then
        "${IMAGE_TOOL}" tag "${mirror}" "${original}"
      fi
      return 0
    fi
  done < <(mirror_candidates "${original}")

  log "回退原始镜像: ${original}"
  DOCKER_CLIENT_TIMEOUT=300 COMPOSE_HTTP_TIMEOUT=300 "${IMAGE_TOOL}" pull "${original}"
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

{
  extract_images
  add_required_images
} | sort -u > "${WORK_DIR}/images/images.txt"
[ -s "${WORK_DIR}/images/images.txt" ] || {
  echo "未从渲染结果中提取到镜像，请检查 Chart 和 values" >&2
  exit 1
}

log "拉取镜像（国内镜像优先）"
while IFS= read -r image; do
  log "pull ${image}"
  attempt=1
  while true; do
    if pull_with_fallback "${image}"; then
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

log "按镜像逐个保存镜像包"
mkdir -p "${WORK_DIR}/images/archives"
while IFS= read -r image; do
  safe_name="$(printf '%s' "${image}" | sed -E 's#[/:@ ]+#_#g; s#_sha256_#_sha256_#g')"
  archive="${WORK_DIR}/images/archives/${safe_name}.tar"
  log "save ${image} -> ${archive}"
  "${IMAGE_TOOL}" save -o "${archive}" "${image}"
done < "${WORK_DIR}/images/images.txt"

log "记录镜像 digest"
while IFS= read -r image; do
  "${IMAGE_TOOL}" image inspect --format='{{index .RepoDigests 0}}' "${image}" 2>/dev/null || echo "${image}"
done < "${WORK_DIR}/images/images.txt" > "${WORK_DIR}/images/images.lock"

log "生成镜像归档清单"
find "${WORK_DIR}/images/archives" -maxdepth 1 -type f -name '*.tar' | sort > "${WORK_DIR}/images/archives.txt"

cat > "${WORK_DIR}/README.md" <<EOF
# ACMP Monitoring Offline Bundle (CN mirror first)

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
EOF

log "打包离线目录"
tar -C "${ROOT_DIR}" -czf "${ROOT_DIR}/${BUNDLE_DIR}.tar.gz" "${BUNDLE_DIR}"

log "完成: ${ROOT_DIR}/${BUNDLE_DIR}.tar.gz"
