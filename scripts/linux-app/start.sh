#!/usr/bin/env bash
set -Eeuo pipefail

# Linux 目录启动脚本：
# 1. 将 Jar 包、配置文件、启动脚本放在同一目录下
# 2. 启动时自动把 stdout / stderr 输出到 log/ 目录
# 3. 适合离线环境和手工排查问题

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

mkdir -p "${LOG_DIR}"

if [[ -z "${JAR_PATH}" ]]; then
  mapfile -t JAR_CANDIDATES < <(find "${APP_HOME}" -maxdepth 1 -type f -name '*.jar' | sort)
  if [[ "${#JAR_CANDIDATES[@]}" -eq 0 ]]; then
    echo "未找到 Jar 包，请把应用 Jar 放到 ${APP_HOME}，或手动设置 JAR_PATH" >&2
    exit 1
  fi
  if [[ "${#JAR_CANDIDATES[@]}" -gt 1 ]]; then
    echo "检测到多个 Jar，请手动指定 JAR_PATH" >&2
    printf '  %s\n' "${JAR_CANDIDATES[@]}"
    exit 1
  fi
  JAR_PATH="${JAR_CANDIDATES[0]}"
fi

if [[ ! -f "${JAR_PATH}" ]]; then
  echo "Jar 不存在: ${JAR_PATH}" >&2
  exit 1
fi

if [[ -f "${PID_FILE}" ]]; then
  OLD_PID="$(cat "${PID_FILE}" || true)"
  if [[ -n "${OLD_PID}" ]] && kill -0 "${OLD_PID}" 2>/dev/null; then
    echo "应用已运行，PID=${OLD_PID}"
    exit 0
  fi
fi

LOG_FILE="${LOG_DIR}/${APP_NAME}.out.log"
ERR_FILE="${LOG_DIR}/${APP_NAME}.err.log"

SPRING_CONFIG_LOCATION_ARG=""
if [[ -d "${CONF_DIR}" ]] || compgen -G "${APP_HOME}/application*.yml" >/dev/null || compgen -G "${APP_HOME}/application*.yaml" >/dev/null; then
  SPRING_CONFIG_LOCATION_ARG="--spring.config.additional-location=file:${CONF_DIR}/"
fi

PROFILES_ARG=""
if [[ -n "${SPRING_PROFILES_ACTIVE}" ]]; then
  PROFILES_ARG="--spring.profiles.active=${SPRING_PROFILES_ACTIVE}"
fi

echo "启动应用：${JAR_PATH}"
echo "日志输出：${LOG_FILE}"
echo "错误输出：${ERR_FILE}"

nohup "${JAVA_BIN}" \
  ${JAVA_OPTS} \
  ${PROFILES_ARG} \
  ${SPRING_CONFIG_LOCATION_ARG} \
  -jar "${JAR_PATH}" \
  >>"${LOG_FILE}" 2>>"${ERR_FILE}" &

APP_PID=$!
echo "${APP_PID}" >"${PID_FILE}"

sleep 1
if kill -0 "${APP_PID}" 2>/dev/null; then
  echo "启动成功，PID=${APP_PID}"
else
  echo "启动失败，请检查 ${ERR_FILE}" >&2
  exit 1
fi
