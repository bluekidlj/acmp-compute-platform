#!/usr/bin/env bash
set -Eeuo pipefail

# 作用：
# 1. 在 Linux 上执行前端 + 后端构建
# 2. 组装成一个可直接拷贝部署的发布目录
# 3. 每次代码有变动，只需要重新执行本脚本即可生成新包

log() {
  local level="$1"
  shift
  printf '[%(%Y-%m-%d %H:%M:%S)T] [%s] %s\n' -1 "${level}" "$*"
}

die() {
  log "ERROR" "$*"
  exit 1
}

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
FRONTEND_DIR="${PROJECT_ROOT}/frontend"
BACKEND_DIR="${PROJECT_ROOT}"

BUILD_ID="$(date +%Y%m%d-%H%M%S)"
OUTPUT_ROOT="${OUTPUT_ROOT:-${PROJECT_ROOT}/release}"
PACKAGE_DIR="${OUTPUT_ROOT}/acmp-${BUILD_ID}"

JAVA_BIN="${JAVA_BIN:-java}"
MVN_BIN="${MVN_BIN:-mvn}"
NPM_BIN="${NPM_BIN:-npm}"

mkdir -p "${PACKAGE_DIR}"
log "INFO" "发布目录: ${PACKAGE_DIR}"

log "INFO" "[1/7] 构建后端 Jar"
(
  cd "${BACKEND_DIR}"
  "${MVN_BIN}" -DskipTests clean package
)

BACKEND_JAR="$(find "${BACKEND_DIR}/target" -maxdepth 1 -type f -name '*.jar' ! -name '*original*' | sort | tail -n 1)"
if [[ -z "${BACKEND_JAR}" ]]; then
  die "未找到后端 Jar，请检查 Maven 构建结果"
fi
log "INFO" "后端 Jar: ${BACKEND_JAR}"

log "INFO" "[2/7] 构建前端静态资源"
(
  cd "${FRONTEND_DIR}"
  "${NPM_BIN}" install
  "${NPM_BIN}" run build
)

if [[ ! -d "${FRONTEND_DIR}/dist" ]]; then
  die "前端 dist 目录不存在，请检查前端构建是否成功"
fi
log "INFO" "前端 dist: ${FRONTEND_DIR}/dist"

log "INFO" "[3/7] 组装发布目录"
mkdir -p "${PACKAGE_DIR}/backend/conf" "${PACKAGE_DIR}/backend/log" "${PACKAGE_DIR}/frontend/log"
cp -f "${BACKEND_JAR}" "${PACKAGE_DIR}/backend/app.jar"

# 后端配置文件：把当前资源目录里的 application.yml 作为发布配置放进去
cp -f "${PROJECT_ROOT}/src/main/resources/application.yml" "${PACKAGE_DIR}/backend/conf/application.yml"

# 复制可选的 logback 配置，确保发布目录内日志行为和源码一致
if [[ -f "${PROJECT_ROOT}/src/main/resources/logback-spring.xml" ]]; then
  cp -f "${PROJECT_ROOT}/src/main/resources/logback-spring.xml" "${PACKAGE_DIR}/backend/conf/logback-spring.xml"
fi

# 复制前端静态资源
cp -a "${FRONTEND_DIR}/dist/." "${PACKAGE_DIR}/frontend/"

log "INFO" "[4/7] 生成前端 Nginx 配置"
cat > "${PACKAGE_DIR}/frontend/nginx.conf" <<'EOF'
worker_processes  1;
error_log  log/nginx-error.log warn;
pid        log/nginx.pid;

events {
    worker_connections  1024;
}

http {
    include       /etc/nginx/mime.types;
    default_type  application/octet-stream;
    sendfile        on;
    keepalive_timeout  65;
    access_log    log/nginx-access.log main;

    log_format main '$remote_addr - $remote_user [$time_local] '
                    '"$request" $status $body_bytes_sent '
                    '"$http_referer" "$http_user_agent" '
                    'rt=$request_time uct="$upstream_connect_time" '
                    'uht="$upstream_header_time" urt="$upstream_response_time"';

    server {
        listen 80;
        server_name _;
        root __FRONTEND_ROOT__;
        index index.html;

        location / {
            try_files $uri $uri/ /index.html;
        }

        location /api/ {
            proxy_pass http://127.0.0.1:8080;
            proxy_set_header Host $host;
            proxy_set_header X-Real-IP $remote_addr;
            proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
            proxy_set_header X-Forwarded-Proto $scheme;
            proxy_connect_timeout 5s;
            proxy_send_timeout 30s;
            proxy_read_timeout 30s;
        }
    }
}
EOF
sed -i "s#__FRONTEND_ROOT__#${PACKAGE_DIR}/frontend#g" "${PACKAGE_DIR}/frontend/nginx.conf"

log "INFO" "[5/7] 生成总启动脚本"
cat > "${PACKAGE_DIR}/start-all.sh" <<'EOF'
#!/usr/bin/env bash
set -Eeuo pipefail

APP_HOME="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_HOME="${APP_HOME}/backend"
FRONTEND_HOME="${APP_HOME}/frontend"
BACKEND_LOG_DIR="${BACKEND_HOME}/log"
BACKEND_PID_FILE="${BACKEND_HOME}/acmp-backend.pid"
BACKEND_STDOUT="${BACKEND_LOG_DIR}/backend.out.log"
BACKEND_STDERR="${BACKEND_LOG_DIR}/backend.err.log"
BOOTSTRAP_LOG="${BACKEND_LOG_DIR}/backend.bootstrap.log"
JAVA_BIN="${JAVA_BIN:-java}"
JAVA_OPTS="${JAVA_OPTS:--Xms512m -Xmx512m -Dfile.encoding=UTF-8}"
NGINX_BIN="${NGINX_BIN:-nginx}"

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

mkdir -p "${BACKEND_LOG_DIR}"

require_file "${BACKEND_HOME}/app.jar"
require_file "${FRONTEND_HOME}/index.html"
require_file "${FRONTEND_HOME}/nginx.conf"
require_file "${BACKEND_HOME}/conf/application.yml"
require_file "${BACKEND_HOME}/conf/logback-spring.xml"

command -v "${JAVA_BIN}" >/dev/null 2>&1 || die "未找到 Java 命令: ${JAVA_BIN}"
command -v "${NGINX_BIN}" >/dev/null 2>&1 || die "未找到 Nginx 命令: ${NGINX_BIN}"

if [[ -f "${BACKEND_PID_FILE}" ]]; then
  OLD_PID="$(cat "${BACKEND_PID_FILE}" || true)"
  if is_process_alive "${OLD_PID}"; then
    log "INFO" "后端已运行，PID=${OLD_PID}"
  else
    log "WARN" "检测到过期 PID 文件，已清理: ${BACKEND_PID_FILE}"
    rm -f "${BACKEND_PID_FILE}"
  fi
fi

if [[ ! -f "${BACKEND_PID_FILE}" ]]; then
  log "INFO" "启动后端"
  log "INFO" "后端 Jar: ${BACKEND_HOME}/app.jar"
  log "INFO" "后端配置: ${BACKEND_HOME}/conf/"
  log "INFO" "Java: $("${JAVA_BIN}" -version 2>&1 | head -n 1)"
  nohup "${JAVA_BIN}" ${JAVA_OPTS} \
    -jar "${BACKEND_HOME}/app.jar" \
    --spring.config.additional-location=file:"${BACKEND_HOME}/conf/" \
    --logging.config=file:"${BACKEND_HOME}/conf/logback-spring.xml" \
    >>"${BOOTSTRAP_LOG}" 2>&1 &
  echo $! > "${BACKEND_PID_FILE}"
  log "INFO" "后端已启动，PID=$(cat "${BACKEND_PID_FILE}")"
fi

mkdir -p "${FRONTEND_HOME}/log"
if [[ -f "${FRONTEND_HOME}/log/nginx.pid" ]]; then
  NGINX_PID="$(cat "${FRONTEND_HOME}/log/nginx.pid" || true)"
  if is_process_alive "${NGINX_PID}"; then
    log "INFO" "Nginx 已运行，PID=${NGINX_PID}"
    exit 0
  else
    log "WARN" "检测到过期 Nginx PID 文件，已清理: ${FRONTEND_HOME}/log/nginx.pid"
    rm -f "${FRONTEND_HOME}/log/nginx.pid"
  fi
fi

log "INFO" "启动 Nginx"
log "INFO" "Nginx 配置: ${FRONTEND_HOME}/nginx.conf"
"${NGINX_BIN}" -p "${FRONTEND_HOME}" -c "${FRONTEND_HOME}/nginx.conf"
log "INFO" "Nginx 已启动"
EOF
chmod +x "${PACKAGE_DIR}/start-all.sh"

cat > "${PACKAGE_DIR}/stop-all.sh" <<'EOF'
#!/usr/bin/env bash
set -Eeuo pipefail

APP_HOME="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_PID_FILE="${APP_HOME}/backend/acmp-backend.pid"
NGINX_PID_FILE="${APP_HOME}/frontend/log/nginx.pid"

kill_by_pid_file() {
  local file="$1"
  if [[ -f "${file}" ]]; then
    local pid
    pid="$(cat "${file}" || true)"
    if [[ -n "${pid}" ]] && kill -0 "${pid}" 2>/dev/null; then
      kill "${pid}"
      echo "已停止 PID=${pid}"
    fi
    rm -f "${file}"
  fi
}

kill_by_pid_file "${BACKEND_PID_FILE}"
kill_by_pid_file "${NGINX_PID_FILE}"
EOF
chmod +x "${PACKAGE_DIR}/stop-all.sh"

cat > "${PACKAGE_DIR}/README.txt" <<EOF
ACMP 发布目录

启动：
  ./start-all.sh

停止：
  ./stop-all.sh

默认访问：
  http://服务器IP/

日志：
  backend/log/backend.out.log
  backend/log/backend.err.log
  frontend/log/nginx-access.log
  frontend/log/nginx-error.log
EOF

log "INFO" "[6/7] 打包完成"
log "INFO" "发布目录：${PACKAGE_DIR}"
log "INFO" "启动命令：cd \"${PACKAGE_DIR}\" && ./start-all.sh"
log "INFO" "[7/7] 已生成 stop-all.sh 和 README.txt"
