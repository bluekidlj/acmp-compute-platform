# Linux 前后端发布与启动说明

这个文档对应当前项目的 MVP 发布方式：

- 前端：打包成静态资源，由 Nginx 托管
- 后端：Spring Boot Jar，目录式启动
- 日志：前后端都落到 `log/` 目录，便于离线排查

## 1. 目标目录结构

执行打包脚本后，会生成一个新的发布目录，类似：

```text
release/
  acmp-20260726-153000/
    backend/
      app.jar
      conf/
        application.yml
      log/
        backend.out.log
        backend.err.log
      acmp-backend.pid
    frontend/
      index.html
      assets/
      nginx.conf
      log/
        nginx-error.log
        nginx.pid
    start-all.sh
```

## 2. 打包方式

在 Linux 环境下进入项目根目录后执行：

```bash
bash scripts/linux-release/build-package.sh
```

脚本会做三件事：

1. 构建后端 Jar
2. 构建前端 `dist/`
3. 组装成一个新的发布目录

每次代码有变动，重新执行一次即可得到新的发布目录。

## 3. 运行前准备

目标 Linux 服务器上需要提前准备：

- `java`
- `nginx`
- 后端可执行 Jar
- 前端静态文件目录

如果你是在内网机器上离线部署，就把打包后的整个发布目录直接拷过去即可。

## 4. 一键启动

进入发布目录后执行：

```bash
cd /opt/acmp/acmp-20260726-153000
./start-all.sh
```

启动脚本会：

- 先启动后端
- 再启动 Nginx
- 自动检查 PID，避免重复启动

## 5. 访问方式

默认访问方式：

- 前端：`http://服务器IP/`
- 后端 API：由 Nginx 代理到 `http://127.0.0.1:8080/api/`

## 6. 日志位置

后端日志：

- `backend/log/backend.out.log`
- `backend/log/backend.err.log`

前端 Nginx 日志：

- `frontend/log/nginx-error.log`
- `frontend/log/nginx.pid`

## 7. 常见排查

### 7.1 页面打不开

先看 Nginx 是否启动：

```bash
ps -ef | grep nginx
```

### 7.2 后端接口 404

确认 Nginx 配置里的 `/api/` 已经转发到 `127.0.0.1:8080/`。

### 7.3 后端没有起来

先看：

```bash
cat backend/log/backend.err.log
cat backend/log/backend.out.log
```

### 7.4 前端刷新后 404

确认 Nginx 配置里保留了：

```nginx
try_files $uri $uri/ /index.html;
```

## 8. 这个方案的边界

这是当前项目适合的 MVP 发布方式，重点是：

- 简单
- 可重复打包
- 日志清楚
- 便于离线调试

后续如果你想再切到 Docker 或 K8s，我们也可以在这个目录结构上继续演进。
