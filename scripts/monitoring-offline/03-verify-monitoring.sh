#!/usr/bin/env bash
set -Eeuo pipefail

# 内网执行：验证监控组件是否安装完成。

if [ -f /etc/kubernetes/admin.conf ]; then
  export KUBECONFIG="${KUBECONFIG:-/etc/kubernetes/admin.conf}"
fi

command -v kubectl >/dev/null 2>&1 || {
  echo "缺少 kubectl" >&2
  exit 1
}

echo
echo "[1/5] monitoring Pod"
kubectl get pods -n monitoring -o wide

echo
echo "[2/5] ServiceMonitor"
kubectl get servicemonitor -A

echo
echo "[3/5] Prometheus Service"
kubectl get svc -n monitoring | grep -E 'prometheus|NAME'

echo
echo "[4/5] Node Exporter DaemonSet"
kubectl get daemonset -n monitoring | grep -E 'node-exporter|NAME'

echo
echo "[5/5] DCGM Exporter"
kubectl get pods -n monitoring -o wide | grep -E 'dcgm|NAME' || true

cat <<'EOF'

ACMP 后端在集群外时，配置：
PROMETHEUS_URL=http://<任一K8s节点内网IP>:30090

如果 GPU Worker 仍是 fake-gpu，DCGM 指标不会有真实 GPU 利用率数据。
EOF

