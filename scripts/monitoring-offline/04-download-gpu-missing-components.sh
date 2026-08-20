#!/usr/bin/env bash
set -Eeuo pipefail

# 下载 GPU Operator / DCGM 缺失组件。
# 也支持把内网诊断脚本生成的 missing-images.txt 作为输入，避免重复猜版本。

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
IMAGE_TOOL="${IMAGE_TOOL:-docker}"
OUTPUT_DIR="${OUTPUT_DIR:-${SCRIPT_DIR}/gpu-missing-components}"
OUTPUT_TAR="${OUTPUT_TAR:-${SCRIPT_DIR}/gpu-missing-components.tar.gz}"
IMAGES_FILE="${IMAGES_FILE:-}"
PULL_RETRIES="${PULL_RETRIES:-3}"
MIRROR_MODE="${MIRROR_MODE:-cn}"

GPU_OPERATOR_VERSION="${GPU_OPERATOR_VERSION:-v25.3.0}"
DEVICE_PLUGIN_VERSION="${DEVICE_PLUGIN_VERSION:-v0.17.1}"
DCGM_EXPORTER_VERSION="${DCGM_EXPORTER_VERSION:-4.1.1-4.0.4-ubuntu22.04}"
VALIDATOR_VERSION="${VALIDATOR_VERSION:-v25.3.0}"

# GPU Operator v25.3.0 官方 Validator 的 linux/amd64 config digest。
# Docker Hub 的 giantswarm 同版本镜像与 NVIDIA 官方镜像 manifest digest 一致，
# 拉取替代镜像后再校验 config digest，避免错误镜像被重新打成 nvcr.io 标签。
VALIDATOR_AMD64_IMAGE_ID="${VALIDATOR_AMD64_IMAGE_ID:-sha256:7e44a407c823370301701efdffd676701a05c91af2a4f954ddb0aa4bb9ab6682}"

DEFAULT_IMAGES=(
  "nvcr.io/nvidia/cloud-native/gpu-operator-validator:${VALIDATOR_VERSION}"
  "nvcr.io/nvidia/k8s-device-plugin:${DEVICE_PLUGIN_VERSION}"
  "nvcr.io/nvidia/k8s/dcgm-exporter:${DCGM_EXPORTER_VERSION}"
)

usage() {
  cat <<'EOF'
用法:
  ./04-download-gpu-missing-components.sh
  ./04-download-gpu-missing-components.sh --images-file missing-images.txt

环境变量:
  IMAGES_FILE  需要下载的镜像清单，一行一个完整 image ref；设置后覆盖默认清单
  OUTPUT_TAR   输出 tar.gz 路径
  IMAGE_TOOL   docker 或 nerdctl，默认 docker
  MIRROR_MODE  cn（国内镜像优先，默认）或 original（只拉原始仓库）
  PULL_RETRIES 每个候选地址重试次数，默认 3

说明:
  GPU Operator v25.3.0 Validator 会优先尝试国内 Docker Hub 代理中的
  giantswarm/gpu-operator-validator:v25.3.0。该镜像的 amd64 manifest
  与 NVIDIA 官方 nvcr.io 镜像一致，校验通过后会重新标记为原始地址。
EOF
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --images-file) IMAGES_FILE="${2:-}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "未知参数: $1" >&2; usage >&2; exit 1 ;;
  esac
done

IMAGES=()
if [ -n "${IMAGES_FILE}" ]; then
  [ -f "${IMAGES_FILE}" ] || { echo "镜像清单不存在: ${IMAGES_FILE}" >&2; exit 1; }
  mapfile -t IMAGES < <(sed -E 's/#.*$//' "${IMAGES_FILE}" | sed '/^[[:space:]]*$/d' | sort -u)
else
  IMAGES=("${DEFAULT_IMAGES[@]}")
fi
[ "${#IMAGES[@]}" -gt 0 ] || { echo "镜像清单为空" >&2; exit 1; }

log() {
  printf '\n[%s] %s\n' "$(date '+%F %T')" "$*"
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "缺少命令: $1" >&2
    exit 1
  }
}

cleanup() {
  if [ -e "${OUTPUT_DIR}" ]; then
    rm -rf "${OUTPUT_DIR}"
  fi
}

require_cmd "${IMAGE_TOOL}"
require_cmd tar
require_cmd sed

image_id() {
  "${IMAGE_TOOL}" image inspect --format '{{.Id}}' "$1" 2>/dev/null \
    || "${IMAGE_TOOL}" inspect --format '{{.Id}}' "$1" 2>/dev/null
}

verify_candidate() {
  local original="$1"
  local candidate="$2"

  case "${original}" in
    nvcr.io/nvidia/cloud-native/gpu-operator-validator:v25.3.0)
      local actual_id
      actual_id="$(image_id "${candidate}" || true)"
      if [ "${actual_id}" != "${VALIDATOR_AMD64_IMAGE_ID}" ]; then
        echo "Validator 镜像校验失败: ${candidate}" >&2
        echo "期望 Image ID: ${VALIDATOR_AMD64_IMAGE_ID}" >&2
        echo "实际 Image ID: ${actual_id:-<unknown>}" >&2
        return 1
      fi
      ;;
  esac
}

mirror_candidates() {
  local original="$1"

  if [ "${MIRROR_MODE}" = "original" ]; then
    printf '%s\n' "${original}"
    return
  fi

  case "${original}" in
    nvcr.io/nvidia/cloud-native/gpu-operator-validator:v25.3.0)
      # 国内公共代理可能临时不可用，因此保留多个候选并最终回退官方源。
      # giantswarm:v25.3.0 的 linux/amd64 manifest digest 与 NVIDIA 官方一致。
      printf '%s\n' \
        "docker.m.daocloud.io/giantswarm/gpu-operator-validator:v25.3.0" \
        "docker.1ms.run/giantswarm/gpu-operator-validator:v25.3.0" \
        "docker.io/giantswarm/gpu-operator-validator:v25.3.0" \
        "${original}"
      ;;
    nvcr.io/nvidia/k8s-device-plugin:v0.17.1)
      printf '%s\n' \
        "swr.cn-north-4.myhuaweicloud.com/ddn-k8s/nvcr.io/nvidia/k8s-device-plugin:v0.17.1" \
        "${original}"
      ;;
    nvcr.io/nvidia/k8s/dcgm-exporter:4.1.1-4.0.4-ubuntu22.04)
      printf '%s\n' \
        "swr.cn-north-4.myhuaweicloud.com/ddn-k8s/nvcr.io/nvidia/k8s/dcgm-exporter:4.1.1-4.0.4-ubuntu22.04" \
        "swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/nvidia/dcgm-exporter:4.1.1-4.0.4-ubuntu22.04" \
        "${original}"
      ;;
    *)
      printf '%s\n' "${original}"
      ;;
  esac
}

pull_with_retry() {
  local image="$1"
  local attempt=1

  while [ "${attempt}" -le "${PULL_RETRIES}" ]; do
    log "pull ${image}（${attempt}/${PULL_RETRIES}）"
    if DOCKER_CLIENT_TIMEOUT=300 COMPOSE_HTTP_TIMEOUT=300 \
      "${IMAGE_TOOL}" pull "${image}"; then
      return 0
    fi
    attempt=$((attempt + 1))
  done

  return 1
}

pull_with_fallback() {
  local original="$1"
  local candidate

  while IFS= read -r candidate; do
    [ -n "${candidate}" ] || continue
    if ! pull_with_retry "${candidate}"; then
      continue
    fi
    if ! verify_candidate "${original}" "${candidate}"; then
      continue
    fi
    if [ "${candidate}" != "${original}" ]; then
      log "tag ${candidate} -> ${original}"
      "${IMAGE_TOOL}" tag "${candidate}" "${original}"
    fi
    return 0
  done < <(mirror_candidates "${original}")

  echo "所有候选镜像地址均拉取失败: ${original}" >&2
  return 1
}

cleanup
mkdir -p "${OUTPUT_DIR}/images"

printf '%s\n' "${IMAGES[@]}" > "${OUTPUT_DIR}/images/images.txt"

log "拉取 GPU 缺失组件镜像"
for image in "${IMAGES[@]}"; do
  pull_with_fallback "${image}"
done

log "导出 GPU 缺失组件镜像"
while IFS= read -r image; do
  [ -n "${image}" ] || continue
  safe_name="$(printf '%s' "${image}" | sed -E 's#[/:@ ]+#_#g')"
  archive="${OUTPUT_DIR}/images/${safe_name}.tar"
  log "save ${image} -> ${archive}"
  "${IMAGE_TOOL}" save -o "${archive}" "${image}"
done < "${OUTPUT_DIR}/images/images.txt"

find "${OUTPUT_DIR}/images" -maxdepth 1 -type f -name '*.tar' | sort > "${OUTPUT_DIR}/images/archives.txt"

cat > "${OUTPUT_DIR}/README.txt" <<EOF
GPU Operator / DCGM 补充镜像包

本包实际包含的镜像见 images/images.txt。
生成来源：${IMAGES_FILE:-默认 GPU 补充清单}

内网使用：

1. 将本目录合并到主离线包目录中，或只把 images/ 里的 tar 拷进去。
2. 然后执行：

   sudo ./scripts/monitoring-offline/02-install-monitoring-offline.sh --load-images

EOF

tar -C "${SCRIPT_DIR}" -czf "${OUTPUT_TAR}" "$(basename "${OUTPUT_DIR}")"

log "完成: ${OUTPUT_TAR}"
