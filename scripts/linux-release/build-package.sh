#!/usr/bin/env bash
set -Eeuo pipefail

# 作用：
# 1. 在 Linux 上执行前端 + 后端构建
# 2. 组装成一个可直接拷贝部署的发布目录
# 3. 每次代码有变动，只需要重新执行本脚本即可生成新包

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

echo "[1/6] 构建后端 Jar"
(
  cd "${BACKEND_DIR}"
  "${MVN_BIN}" -DskipTests package
)

BACKEND_JAR="$(find "${BACKEND_DIR}/target" -maxdepth 1 -type f -name '*.jar' ! -name '*original*' | sort | tail -n 1)"
if [[ -z "${BACKEND_JAR}" ]]; then
  echo "未找到后端 Jar，请检查 Maven 构建结果" >&2
  exit 1
fi

echo "[2/6] 构建前端静态资源"
(
  cd "${FRONTEND_DIR}"
  "${NPM_BIN}" install
  "${NPM_BIN}" run build
)

if [[ ! -d "${FRONTEND_DIR}/dist" ]]; then
  echo "前端 dist 目录不存在，请检查前端构建是否成功" >&2
  exit 1
fi

echo "[3/6] 组装发布目录"
mkdir -p "${PACKAGE_DIR}/backend/conf" "${PACKAGE_DIR}/backend/log" "${PACKAGE_DIR}/frontend"
cp -f "${BACKEND_JAR}" "${PACKAGE_DIR}/backend/app.jar"

# 后端配置文件：把当前资源目录里的 application.yml 作为发布配置放进去
cp -f "${PROJECT_ROOT}/src/main/resources/application.yml" "${PACKAGE_DIR}/backend/conf/application.yml"

# 复制前端静态资源
cp -a "${FRONTEND_DIR}/dist/." "${PACKAGE_DIR}/frontend/"

echo "[4/6] 生成前端 Nginx 配置"
cat > "${PACKAGE_DIR}/frontend/nginx.conf" <<'EOF'
worker_processes  1;
error_log  log/nginx-error.log warn;
pid        log/nginx.pid;

events {
    worker_connections  1024;
}

http {
    include       mime.types;
    default_type  application/octet-stream;
    sendfile        on;
    keepalive_timeout  65;

    server {
        listen 80;
        server_name _;
        root __FRONTEND_ROOT__;
        index index.html;

        location / {
            try_files $uri $uri/ /index.html;
        }

        location /api/ {
            proxy_pass http://127.0.0.1:8080/;
            proxy_set_header Host $host;
            proxy_set_header X-Real-IP $remote_addr;
            proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
            proxy_set_header X-Forwarded-Proto $scheme;
        }
    }
}
EOF
sed -i "s#__FRONTEND_ROOT__#${PACKAGE_DIR}/frontend#g" "${PACKAGE_DIR}/frontend/nginx.conf"

echo "[5/6] 生成总启动脚本"
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
JAVA_BIN="${JAVA_BIN:-java}"
JAVA_OPTS="${JAVA_OPTS:--Xms512m -Xmx512m -Dfile.encoding=UTF-8}"

mkdir -p "${BACKEND_LOG_DIR}"

if [[ ! -f "${BACKEND_HOME}/app.jar" ]]; then
  echo "找不到后端 Jar: ${BACKEND_HOME}/app.jar" >&2
  exit 1
fi

if [[ -f "${BACKEND_PID_FILE}" ]]; then
  OLD_PID="$(cat "${BACKEND_PID_FILE}" || true)"
  if [[ -n "${OLD_PID}" ]] && kill -0 "${OLD_PID}" 2>/dev/null; then
    echo "后端已运行，PID=${OLD_PID}"
  else
    rm -f "${BACKEND_PID_FILE}"
  fi
fi

if [[ ! -f "${BACKEND_PID_FILE}" ]]; then
  nohup "${JAVA_BIN}" ${JAVA_OPTS} \
    -jar "${BACKEND_HOME}/app.jar" \
    --spring.config.additional-location=file:"${BACKEND_HOME}/conf/" \
    >>"${BACKEND_STDOUT}" 2>>"${BACKEND_STDERR}" &
  echo $! > "${BACKEND_PID_FILE}"
  echo "后端已启动"
fi

if command -v nginx >/dev/null 2>&1; then
  if [[ ! -f "${FRONTEND_HOME}/nginx.conf" ]]; then
    echo "找不到前端 nginx.conf: ${FRONTEND_HOME}/nginx.conf" >&2
    exit 1
  fi
  if [[ -f "${FRONTEND_HOME}/log/nginx.pid" ]]; then
    NGINX_PID="$(cat "${FRONTEND_HOME}/log/nginx.pid" || true)"
    if [[ -n "${NGINX_PID}" ]] && kill -0 "${NGINX_PID}" 2>/dev/null; then
      echo "Nginx 已运行，PID=${NGINX_PID}"
      exit 0
    fi
  fi
  mkdir -p "${FRONTEND_HOME}/log"
  nginx -p "${FRONTEND_HOME}" -c "${FRONTEND_HOME}/nginx.conf"
  echo "Nginx 已启动"
else
  echo "未检测到 nginx 命令，请先安装 nginx 再启动前端" >&2
  exit 1
fi
EOF
chmod +x "${PACKAGE_DIR}/start-all.sh"

echo "[6/6] 打包完成"
echo "发布目录：${PACKAGE_DIR}"
echo "启动命令：cd \"${PACKAGE_DIR}\" && ./start-all.sh"
