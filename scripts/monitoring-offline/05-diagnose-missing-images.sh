#!/usr/bin/env bash
set -Eeuo pipefail

# 在内网 Master 上执行。扫描整个集群的 Pod/InitContainer 和拉取失败事件，
# 输出下一次外网下载所需的完整镜像清单，不需要逐个节点手工翻日志。

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUTPUT_DIR="${OUTPUT_DIR:-${PWD}/monitoring-image-diagnosis-$(date +%Y%m%d-%H%M%S)}"
CONTAINERD_NAMESPACE="${CONTAINERD_NAMESPACE:-k8s.io}"

usage() {
  cat <<'EOF'
用法:
  sudo ./05-diagnose-missing-images.sh
  sudo OUTPUT_DIR=/tmp/acmp-diagnosis ./05-diagnose-missing-images.sh

输出:
  cluster-images.txt             集群当前所有 Pod 引用的镜像
  failed-pull-images.txt         ImagePullBackOff/ErrImagePull 相关镜像
  missing-images.txt             需要补下载的镜像（可直接给 04 脚本）
  download-missing-images.sh     外网执行的下载打包命令脚本
  report.txt                     节点、Pod、事件汇总
EOF
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    -h|--help) usage; exit 0 ;;
    *) echo "未知参数: $1" >&2; usage >&2; exit 1 ;;
  esac
done

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || { echo "缺少命令: $1" >&2; exit 1; }
}

require_cmd kubectl
require_cmd sort
require_cmd sed
require_cmd awk
require_cmd python3

mkdir -p "${OUTPUT_DIR}"
PODS_JSON="${OUTPUT_DIR}/pods.json"
EVENTS_FILE="${OUTPUT_DIR}/events.txt"
kubectl get pods -A -o json > "${PODS_JSON}"
kubectl get events -A --sort-by=.lastTimestamp > "${EVENTS_FILE}" 2>&1 || true

python3 - "${PODS_JSON}" "${OUTPUT_DIR}" <<'PY'
import json, re, sys
from pathlib import Path

pods = json.loads(Path(sys.argv[1]).read_text(encoding='utf-8'))
out = Path(sys.argv[2])
all_images = set()
failed = set()
report = []

def image_names(spec):
    for key in ('initContainers', 'containers', 'ephemeralContainers'):
        for c in spec.get(key) or []:
            image = c.get('image')
            if image:
                yield image

for item in pods.get('items', []):
    meta, spec, status = item.get('metadata', {}), item.get('spec', {}), item.get('status', {})
    ns = meta.get('namespace', 'default')
    name = meta.get('name', '-')
    node = spec.get('nodeName', '<未调度>')
    pod_images = list(image_names(spec))
    all_images.update(pod_images)
    bad = []
    for cs in (status.get('initContainerStatuses') or []) + (status.get('containerStatuses') or []):
        state = cs.get('state') or {}
        waiting = state.get('waiting') or {}
        reason = waiting.get('reason', '')
        if reason in ('ErrImagePull', 'ImagePullBackOff'):
            image = cs.get('image') or cs.get('imageID') or '-'
            if image != '-':
                failed.add(image)
            bad.append(f"{cs.get('name','-')}={reason}:{image}")
    if bad:
        report.append(f"{ns}/{name}\tnode={node}\t" + ' '.join(bad))

(out / 'cluster-images.txt').write_text('\n'.join(sorted(all_images)) + ('\n' if all_images else ''), encoding='utf-8')
(out / 'failed-pull-images.txt').write_text('\n'.join(sorted(failed)) + ('\n' if failed else ''), encoding='utf-8')
(out / 'pod-failures.txt').write_text('\n'.join(report) + ('\n' if report else ''), encoding='utf-8')
PY

# 事件中常见格式：Failed to pull image "..." / Back-off pulling image "..."
sed -nE 's/.*(Failed to pull image|Back-off pulling image|pulling image) "([^"]+)".*/\2/p' "${EVENTS_FILE}" \
  | sort -u > "${OUTPUT_DIR}/event-pull-images.txt"

cat "${OUTPUT_DIR}/failed-pull-images.txt" "${OUTPUT_DIR}/event-pull-images.txt" \
  | sed '/^[[:space:]]*$/d' | sort -u > "${OUTPUT_DIR}/failed-pull-images.all.txt"

# 当前节点 containerd 中已有的镜像只用于辅助判断；Pod 所在节点可能不是当前执行节点。
if command -v ctr >/dev/null 2>&1; then
  ctr -n "${CONTAINERD_NAMESPACE}" images ls 2>/dev/null \
    | awk 'NR > 1 {print $1}' | sort -u > "${OUTPUT_DIR}/local-containerd-images.txt" || true
else
  : > "${OUTPUT_DIR}/local-containerd-images.txt"
fi

# 失败镜像按集群维度全部保留。当前命令可能在 Master 执行，而失败 Pod
# 可能被调度到 Worker；不能因为 Master 本机已有镜像就误判 Worker 不需要补包。
cp "${OUTPUT_DIR}/failed-pull-images.all.txt" "${OUTPUT_DIR}/missing-images.txt"
comm -12 "${OUTPUT_DIR}/failed-pull-images.all.txt" "${OUTPUT_DIR}/local-containerd-images.txt" \
  > "${OUTPUT_DIR}/local-present-failed-images.txt"

cat > "${OUTPUT_DIR}/download-missing-images.sh" <<'EOF'
#!/usr/bin/env bash
set -Eeuo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
IMAGE_LIST="${IMAGE_LIST:-${SCRIPT_DIR}/missing-images.txt}"
OUTPUT_TAR="${OUTPUT_TAR:-${SCRIPT_DIR}/missing-images-$(date +%Y%m%d-%H%M%S).tar.gz}"
IMAGE_TOOL="${IMAGE_TOOL:-docker}"
[ -s "${IMAGE_LIST}" ] || { echo "没有待下载镜像: ${IMAGE_LIST}"; exit 0; }
command -v "${IMAGE_TOOL}" >/dev/null 2>&1 || { echo "缺少 ${IMAGE_TOOL}" >&2; exit 1; }
WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT
mkdir -p "${WORK}/images"
cp "${IMAGE_LIST}" "${WORK}/images/images.txt"
while IFS= read -r image; do
  [ -n "${image}" ] || continue
  "${IMAGE_TOOL}" pull "${image}"
  safe="$(printf '%s' "${image}" | sed -E 's#[/:@ ]+#_#g')"
  "${IMAGE_TOOL}" save -o "${WORK}/images/${safe}.tar" "${image}"
done < "${WORK}/images/images.txt"
find "${WORK}/images" -maxdepth 1 -type f -name '*.tar' | sort > "${WORK}/images/archives.txt"
tar -C "${WORK}" -czf "${OUTPUT_TAR}" images
echo "已生成: ${OUTPUT_TAR}"
EOF
chmod +x "${OUTPUT_DIR}/download-missing-images.sh"

cat > "${OUTPUT_DIR}/report.txt" <<EOF
ACMP 监控镜像诊断报告
生成时间: $(date '+%F %T')
执行节点: $(hostname)
containerd namespace: ${CONTAINERD_NAMESPACE}

失败 Pod:
$(cat "${OUTPUT_DIR}/pod-failures.txt" 2>/dev/null || true)

待补下载镜像:
$(cat "${OUTPUT_DIR}/missing-images.txt" 2>/dev/null || true)

当前执行节点已经存在、但其他节点仍可能缺失的镜像:
$(cat "${OUTPUT_DIR}/local-present-failed-images.txt" 2>/dev/null || true)

说明:
- missing-images.txt 可直接作为 04-download-gpu-missing-components.sh --images-file 的输入。
- download-missing-images.sh 可复制到外网机器，与 missing-images.txt 放在同一目录后执行。
- 如果 Pod 调度到了其他节点，请在每台节点执行 --load-images；当前节点的 ctr 结果只能代表当前节点。
EOF

echo "诊断完成: ${OUTPUT_DIR}"
echo "待补下载镜像数量: $(wc -l < "${OUTPUT_DIR}/missing-images.txt" | tr -d ' ')"
echo "查看报告: ${OUTPUT_DIR}/report.txt"
