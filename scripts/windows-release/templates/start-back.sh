#!/usr/bin/env bash
set -Eeuo pipefail

APP_HOME="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_HOME="${APP_HOME}/back-end"
CONF_DIR="${BACKEND_HOME}/conf"
LOG_DIR="${LOG_DIR:-${BACKEND_HOME}/log}"
DATA_DIR="${BACKEND_HOME}/data"
JAR_PATH="${BACKEND_HOME}/acmp-compute.jar"
PID_FILE="${BACKEND_HOME}/acmp-backend.pid"
JAVA_BIN="${JAVA_BIN:-java}"
JAVA_OPTS="${JAVA_OPTS:--Xms512m -Xmx1024m -Dfile.encoding=UTF-8}"

die() {
  echo "[ERROR] $*" >&2
  exit 1
}

[[ -f "${JAR_PATH}" ]] || die "missing backend JAR: ${JAR_PATH}"
[[ -f "${CONF_DIR}/application.yml" ]] || die "missing backend config: ${CONF_DIR}/application.yml"
command -v "${JAVA_BIN}" >/dev/null 2>&1 || die "java command not found: ${JAVA_BIN}"

mkdir -p "${LOG_DIR}" "${DATA_DIR}"
if [[ -f "${PID_FILE}" ]]; then
  OLD_PID="$(cat "${PID_FILE}" || true)"
  if [[ -n "${OLD_PID}" ]] && kill -0 "${OLD_PID}" 2>/dev/null; then
    echo "backend already running, PID=${OLD_PID}"
    exit 0
  fi
  rm -f "${PID_FILE}"
fi

export LOG_DIR
cd "${BACKEND_HOME}"
nohup "${JAVA_BIN}" ${JAVA_OPTS} \
  -jar "${JAR_PATH}" \
  --spring.config.additional-location="file:${CONF_DIR}/" \
  --logging.config="file:${CONF_DIR}/logback-spring.xml" \
  >>"${LOG_DIR}/backend.bootstrap.log" 2>&1 &
echo $! >"${PID_FILE}"

sleep 2
PID="$(cat "${PID_FILE}")"
if ! kill -0 "${PID}" 2>/dev/null; then
  rm -f "${PID_FILE}"
  die "backend failed to start; check ${LOG_DIR}/backend.bootstrap.log"
fi
echo "backend started, PID=${PID}, port=8080"

