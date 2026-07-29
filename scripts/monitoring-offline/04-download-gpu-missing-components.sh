#!/usr/bin/env bash
set -Eeuo pipefail

# 下载 GPU Operator / DCGM 缺失组件。
# 也支持把内网诊断脚本生成的 missing-images.txt 作为输入，避免重复猜版本。

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
IMAGE_TOOL="${IMAGE_TOOL:-docker}"
OUTPUT_DIR="${OUTPUT_DIR:-${SCRIPT_DIR}/gpu-missing-components}"
OUTPUT_TAR="${OUTPUT_TAR:-${SCRIPT_DIR}/gpu-missing-components.tar.gz}"
IMAGES_FILE="${IMAGES_FILE:-}"

GPU_OPERATOR_VERSION="${GPU_OPERATOR_VERSION:-v25.3.0}"
DEVICE_PLUGIN_VERSION="${DEVICE_PLUGIN_VERSION:-v0.17.1}"
DCGM_EXPORTER_VERSION="${DCGM_EXPORTER_VERSION:-4.1.1-4.0.4-ubuntu22.04}"
VALIDATOR_VERSION="${VALIDATOR_VERSION:-v25.3.0}"

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

cleanup
mkdir -p "${OUTPUT_DIR}/images"

printf '%s\n' "${IMAGES[@]}" > "${OUTPUT_DIR}/images/images.txt"

log "拉取 GPU 缺失组件镜像"
for image in "${IMAGES[@]}"; do
  log "pull ${image}"
  "${IMAGE_TOOL}" pull "${image}"
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
