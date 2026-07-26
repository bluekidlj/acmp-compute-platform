#!/usr/bin/env bash
set -Eeuo pipefail

# 在 Worker 节点执行。
# 用法：
#   sudo JOIN_COMMAND='kubeadm join 192.168.1.10:6443 ...' ./20-join-worker.sh

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JOIN_COMMAND="${JOIN_COMMAND:-}"

[[ "${EUID}" -eq 0 ]] || { echo "请使用 sudo 运行"; exit 1; }
[[ -n "${JOIN_COMMAND}" ]] || {
  echo "缺少 JOIN_COMMAND，请从 Master 的 /root/acmp-worker-join.sh 获取 join 命令" >&2
  exit 1
}

if [[ "${SKIP_COMMON_INSTALL:-0}" != "1" ]]; then
  "${SCRIPT_DIR}/00-install-common.sh"
fi

if [[ -f /etc/kubernetes/kubelet.conf ]]; then
  echo "该 Worker 已加入 Kubernetes，跳过 kubeadm join"
else
  # JOIN_COMMAND 来自可信 Master 管理员；使用数组执行，避免 eval 二次解释。
  read -r -a join_parts <<<"${JOIN_COMMAND}"
  if [[ " ${JOIN_COMMAND} " == *" --cri-socket "* ]]; then
    "${join_parts[@]}"
  else
    "${join_parts[@]}" --cri-socket unix:///run/containerd/containerd.sock
  fi
fi

systemctl restart kubelet
echo "Worker 加入请求已完成，请回到 Master 执行：kubectl get nodes -o wide"
