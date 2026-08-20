#!/usr/bin/env bash
set -Eeuo pipefail

APP_HOME="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NGINX_BIN="${NGINX_BIN:-nginx}"
NGINX_CONF="${APP_HOME}/nginx.conf"
NGINX_PID_FILE="${APP_HOME}/back-end/log/nginx.pid"

die() {
  echo "[ERROR] $*" >&2
  exit 1
}

[[ -f "${APP_HOME}/front-end/index.html" ]] || die "missing front-end/index.html"
[[ -f "${NGINX_CONF}" ]] || die "missing nginx.conf"
command -v "${NGINX_BIN}" >/dev/null 2>&1 || die "nginx command not found: ${NGINX_BIN}"
mkdir -p "${APP_HOME}/back-end/log"

if [[ -f "${NGINX_PID_FILE}" ]]; then
  PID="$(cat "${NGINX_PID_FILE}" || true)"
  if [[ -n "${PID}" ]] && kill -0 "${PID}" 2>/dev/null; then
    echo "frontend already running, nginx PID=${PID}"
    exit 0
  fi
  rm -f "${NGINX_PID_FILE}"
fi

"${NGINX_BIN}" -t -p "${APP_HOME}/" -c "${NGINX_CONF}"
"${NGINX_BIN}" -p "${APP_HOME}/" -c "${NGINX_CONF}"
echo "frontend started, port=80"

