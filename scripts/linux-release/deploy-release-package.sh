#!/usr/bin/env bash
set -Eeuo pipefail

# 作用：
# 1. 从 /root/acmp/release 自动找到最新的发布压缩包
# 2. 解压到 /opt/acmp/runs
# 3. 如果旧进程存在，先停止再启动
# 4. 自动执行发布目录里的 start-all.sh
# 5. 循环检查 /actuator/health，直到通过或超时

log() {
  local level="$1"
  shift
  printf '[%(%Y-%m-%d %H:%M:%S)T] [%s] %s\n' -1 "${level}" "$*"
}

die() {
  log "ERROR" "$*"
  exit 1
}

RELEASE_DIR="${RELEASE_DIR:-/root/acmp/release}"
RUN_ROOT="${RUN_ROOT:-/opt/acmp/runs}"
PACKAGE_PATH="${1:-}"
HEALTH_URL="${HEALTH_URL:-http://127.0.0.1:8080/actuator/health}"
HEALTH_TIMEOUT_SECONDS="${HEALTH_TIMEOUT_SECONDS:-180}"
SLEEP_SECONDS="${SLEEP_SECONDS:-3}"

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || die "缺少命令: $1"
}

require_cmd tar
require_cmd curl

if [[ -z "${PACKAGE_PATH}" ]]; then
  PACKAGE_PATH="$(find "${RELEASE_DIR}" -maxdepth 1 -type f -name '*.tar.gz' | sort | tail -n 1 || true)"
fi

[[ -n "${PACKAGE_PATH}" ]] || die "未找到发布压缩包，请把 tar.gz 放到 ${RELEASE_DIR}"
[[ -f "${PACKAGE_PATH}" ]] || die "发布包不存在: ${PACKAGE_PATH}"

mkdir -p "${RUN_ROOT}"

PACKAGE_NAME="$(basename "${PACKAGE_PATH}" .tar.gz)"
TARGET_DIR="${RUN_ROOT}/${PACKAGE_NAME}"

if [[ -d "${TARGET_DIR}" ]]; then
  log "WARN" "清理旧运行目录: ${TARGET_DIR}"
  rm -rf "${TARGET_DIR}"
fi

log "INFO" "解压发布包: ${PACKAGE_PATH}"
tar -xzf "${PACKAGE_PATH}" -C "${RUN_ROOT}"

if [[ ! -d "${TARGET_DIR}" ]]; then
  die "解压后未找到目录: ${TARGET_DIR}"
fi

if [[ ! -x "${TARGET_DIR}/start-all.sh" ]]; then
  die "发布目录缺少可执行的 start-all.sh: ${TARGET_DIR}/start-all.sh"
fi

if [[ -x "${TARGET_DIR}/stop-all.sh" ]]; then
  log "INFO" "先停止旧进程（如果存在）"
  (
    cd "${TARGET_DIR}"
    ./stop-all.sh || true
  )
fi

log "INFO" "启动发布目录: ${TARGET_DIR}"
(
  cd "${TARGET_DIR}"
  ./start-all.sh
)

log "INFO" "等待健康检查通过: ${HEALTH_URL}"
deadline="$((SECONDS + HEALTH_TIMEOUT_SECONDS))"
while [[ "${SECONDS}" -lt "${deadline}" ]]; do
  if curl -fsS "${HEALTH_URL}" >/dev/null 2>&1; then
    log "INFO" "健康检查通过"
    exit 0
  fi
  sleep "${SLEEP_SECONDS}"
done

die "健康检查超时: ${HEALTH_URL}"
