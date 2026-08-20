# 前端功能开关

当前 MVP 支持通过后端 `application.yml` 控制前端功能入口。

## 创新实验室

```yaml
acmp:
  features:
    innovation-lab-enabled: false
```

也可以通过环境变量 `INNOVATION_LAB_ENABLED=true|false` 覆盖。

- `false`：隐藏创新实验室菜单，直接访问相关 URL 时跳转平台首页。
- `true`：显示负载感知、数字孪生和策略仿真入口。

功能开关由后端接口返回，前端构建产物无需因环境不同而重新编译。
