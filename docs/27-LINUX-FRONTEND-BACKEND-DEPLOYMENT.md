# Linux 前后端发布与一键部署说明

这个文档对应当前项目的 MVP 发布方式：

- 前端：打包成静态资源，由 Nginx 托管
- 后端：Spring Boot Jar，目录式启动
- 部署：把压缩包放到 `/root/acmp/release`，执行 `deploy.sh` 自动解压、启动、健康检查

## 1. 目标目录结构

执行打包脚本后，会生成一个新的发布目录，类似：

```text
/opt/acmp/runs/
  acmp-20260726-153000/
    backend/
      app.jar
      conf/
        application.yml
        logback-spring.xml
      log/
        backend.bootstrap.log
        backend.out.log
        backend.err.log
      acmp-backend.pid
    frontend/
      index.html
      assets/
      nginx.conf
      log/
        nginx-access.log
        nginx-error.log
        nginx.pid
    deploy.sh
    start-all.sh
    stop-all.sh
    README.txt
```

## 2. 打包方式

在 Linux 环境下进入项目根目录后执行：

```bash
bash scripts/linux-release/build-package.sh
```

脚本会做三件事：

1. 构建后端 Jar
2. 构建前端 `dist/`
3. 组装成一个新的发布目录，并生成 `deploy.sh`

发布产物默认放到：

```text
/opt/acmp/release
```

## 3. 一键部署

把生成的压缩包放到：

```text
/root/acmp/release
```

然后在目标 Linux 服务器上执行：

```bash
cd /root/acmp/release
bash deploy.sh
```

`deploy.sh` 会自动：

1. 选择最新的 `*.tar.gz`；
2. 解压到 `/opt/acmp/runs`；
3. 先执行发布目录里的 `stop-all.sh`，停止旧进程；
4. 再执行 `start-all.sh` 启动新版本；
5. 循环检查 `http://127.0.0.1:8080/actuator/health`；
6. 健康检查通过后退出。

如果你想手动指定包，也可以：

```bash
bash deploy.sh /root/acmp/release/acmp-20260726-153000.tar.gz
```

## 4. 运行前准备

目标 Linux 服务器上需要提前准备：

- `java`
- `nginx`
- 后端可执行 Jar
- 前端静态文件目录

如果你是在内网机器上离线部署，就把打包后的压缩包直接拷过去即可。

## 5. 一键启动

如果你已经进入发布目录，也可以直接执行：

```bash
cd /opt/acmp/runs/acmp-20260726-153000
./start-all.sh
```

启动脚本会：

- 先启动后端
- 再启动 Nginx
- 自动检查 PID，避免重复启动

## 6. 停止

进入发布目录后执行：

```bash
./stop-all.sh
```

## 7. 访问方式

默认访问方式：

- 前端：`http://服务器IP/`
- 后端 API：由 Nginx 代理到 `http://127.0.0.1:8080/api/`
- 健康检查：`http://127.0.0.1:8080/actuator/health`

## 8. 日志位置

后端日志：

- `backend/log/backend.bootstrap.log`
- `backend/log/backend.out.log`
- `backend/log/backend.err.log`

前端 Nginx 日志：

- `frontend/log/nginx-access.log`
- `frontend/log/nginx-error.log`

## 9. 常见排查

### 9.1 页面打不开

先看 Nginx 是否启动：

```bash
ps -ef | grep nginx
```

### 9.2 后端接口 404

确认 Nginx 配置里的 `/api/` 已经转发到 `127.0.0.1:8080/`。

### 9.3 后端没有起来

先看：

```bash
cat backend/log/backend.bootstrap.log
cat backend/log/backend.err.log
```

### 9.4 前端刷新后 404

确认 Nginx 配置里保留了：

```nginx
try_files $uri $uri/ /index.html;
```

### 9.5 500 Internal Server Error

优先检查发布目录是否在 `/root`。  
如果在 `/root`，Nginx 可能没有权限读取静态文件。  
建议始终使用 `/opt/acmp/runs` 下的发布目录。

## 10. 这个方案的边界

这是当前项目适合的 MVP 发布方式，重点是：

- 简单
- 可重复打包
- 日志清楚
- 便于离线调试

后续如果你想再切到 Docker 或 K8s，我们也可以在这个目录结构上继续演进。
