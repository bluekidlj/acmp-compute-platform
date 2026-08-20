#!/usr/bin/env bash
set -Eeuo pipefail

APP_HOME="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_PID_FILE="${APP_HOME}/back-end/acmp-backend.pid"
NGINX_PID_FILE="${APP_HOME}/back-end/log/nginx.pid"
NGINX_BIN="${NGINX_BIN:-nginx}"
NGINX_CONF="${APP_HOME}/nginx.conf"

if command -v "${NGINX_BIN}" >/dev/null 2>&1 && [[ -f "${NGINX_CONF}" ]]; then
  "${NGINX_BIN}" -p "${APP_HOME}/" -c "${NGINX_CONF}" -s quit >/dev/null 2>&1 || true
fi
if [[ -f "${NGINX_PID_FILE}" ]]; then
  NGINX_PID="$(cat "${NGINX_PID_FILE}" || true)"
  if [[ -n "${NGINX_PID}" ]] && kill -0 "${NGINX_PID}" 2>/dev/null; then
    kill "${NGINX_PID}" 2>/dev/null || true
  fi
  rm -f "${NGINX_PID_FILE}"
fi

if [[ -f "${BACKEND_PID_FILE}" ]]; then
  BACKEND_PID="$(cat "${BACKEND_PID_FILE}" || true)"
  if [[ -n "${BACKEND_PID}" ]] && kill -0 "${BACKEND_PID}" 2>/dev/null; then
    kill "${BACKEND_PID}"
    for _ in {1..15}; do
      kill -0 "${BACKEND_PID}" 2>/dev/null || break
      sleep 1
    done
    if kill -0 "${BACKEND_PID}" 2>/dev/null; then
      kill -9 "${BACKEND_PID}"
    fi
  fi
  rm -f "${BACKEND_PID_FILE}"
fi

echo "frontend and backend stopped"

