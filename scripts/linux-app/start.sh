#!/usr/bin/env bash
set -Eeuo pipefail

# Linux 目录启动脚本：
# 1. 将 Jar 包、配置文件、启动脚本放在同一目录下
# 2. 启动时自动把 stdout / stderr 输出到 log/ 目录
# 3. 适合离线环境和手工排查问题

log() {
  local level="$1"
  shift
  printf '[%(%Y-%m-%d %H:%M:%S)T] [%s] %s\n' -1 "${level}" "$*"
}

die() {
  log "ERROR" "$*"
  exit 1
}

require_file() {
  [[ -f "$1" ]] || die "缺少文件: $1"
}

is_process_alive() {
  local pid="$1"
  [[ -n "${pid}" ]] && kill -0 "${pid}" 2>/dev/null
}

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_HOME="${APP_HOME:-${SCRIPT_DIR}}"
LOG_DIR="${LOG_DIR:-${APP_HOME}/log}"
CONF_DIR="${CONF_DIR:-${APP_HOME}/conf}"
JAR_PATH="${JAR_PATH:-}"
JAVA_BIN="${JAVA_BIN:-java}"
JAVA_OPTS="${JAVA_OPTS:--Xms512m -Xmx512m -Dfile.encoding=UTF-8}"
SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-}"
APP_NAME="${APP_NAME:-acmp-compute}"
PID_FILE="${PID_FILE:-${APP_HOME}/${APP_NAME}.pid}"
BOOTSTRAP_LOG="${BOOTSTRAP_LOG:-${LOG_DIR}/${APP_NAME}.bootstrap.log}"

mkdir -p "${LOG_DIR}"

if [[ -z "${JAR_PATH}" ]]; then
  mapfile -t JAR_CANDIDATES < <(find "${APP_HOME}" -maxdepth 1 -type f -name '*.jar' | sort)
  if [[ "${#JAR_CANDIDATES[@]}" -eq 0 ]]; then
    die "未找到 Jar 包，请把应用 Jar 放到 ${APP_HOME}，或手动设置 JAR_PATH"
  fi
  if [[ "${#JAR_CANDIDATES[@]}" -gt 1 ]]; then
    log "ERROR" "检测到多个 Jar，请手动指定 JAR_PATH"
    printf '  %s\n' "${JAR_CANDIDATES[@]}"
    exit 1
  fi
  JAR_PATH="${JAR_CANDIDATES[0]}"
fi

require_file "${JAR_PATH}"
require_file "${CONF_DIR}/application.yml"

if [[ -f "${PID_FILE}" ]]; then
  OLD_PID="$(cat "${PID_FILE}" || true)"
  if is_process_alive "${OLD_PID}"; then
    log "INFO" "应用已运行，PID=${OLD_PID}"
    exit 0
  fi
  log "WARN" "检测到过期 PID 文件，已清理: ${PID_FILE}"
  rm -f "${PID_FILE}"
fi

LOG_FILE="${LOG_DIR}/${APP_NAME}.out.log"
ERR_FILE="${LOG_DIR}/${APP_NAME}.err.log"

SPRING_CONFIG_LOCATION_ARG=""
SPRING_CONFIG_LOCATION_ARG="--spring.config.additional-location=file:${CONF_DIR}/"

PROFILES_ARG=""
if [[ -n "${SPRING_PROFILES_ACTIVE}" ]]; then
  PROFILES_ARG="--spring.profiles.active=${SPRING_PROFILES_ACTIVE}"
fi

log "INFO" "启动应用: ${JAR_PATH}"
log "INFO" "配置目录: ${CONF_DIR}"
log "INFO" "业务日志: ${CONF_DIR}/../log/${APP_NAME}.log"
log "INFO" "启动诊断日志: ${BOOTSTRAP_LOG}"
log "INFO" "Java: $("${JAVA_BIN}" -version 2>&1 | head -n 1)"

nohup "${JAVA_BIN}" \
  ${JAVA_OPTS} \
  ${PROFILES_ARG} \
  ${SPRING_CONFIG_LOCATION_ARG} \
  --logging.config=file:"${CONF_DIR}/logback-spring.xml" \
  -jar "${JAR_PATH}" \
  >>"${BOOTSTRAP_LOG}" 2>&1 &

APP_PID=$!
echo "${APP_PID}" >"${PID_FILE}"

sleep 1
if kill -0 "${APP_PID}" 2>/dev/null; then
  log "INFO" "启动成功，PID=${APP_PID}"
else
  die "启动失败，请检查 ${ERR_FILE}"
fi
