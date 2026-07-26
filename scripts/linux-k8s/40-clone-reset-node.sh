#!/bin/sh
set -eu

# ACMP Demo 克隆后节点重置脚本。
# 适用于把已经装好基础环境的 VM 克隆成新机器后，第一次启动前或启动后执行。
# 作用：清理 Kubernetes 残留、重置机器身份、保留基础系统和已安装的软件。

log() {
  printf '\n[%s] %s\n' "$(date '+%F %T')" "$*"
}

fail() {
  echo "错误: $*" >&2
  exit 1
}

require_root() {
  [ "$(id -u)" -eq 0 ] || fail "请使用 sudo 运行此脚本"
  [ -f /etc/os-release ] || fail "无法识别 Linux 发行版"
  . /etc/os-release
  [ "${ID}" = "ubuntu" ] || fail "当前脚本只支持 Ubuntu"
}

stop_kubernetes_services() {
  log "停止 kubelet"
  systemctl stop kubelet >/dev/null 2>&1 || true
  systemctl disable kubelet >/dev/null 2>&1 || true
}

reset_kubernetes_state() {
  if command -v kubeadm >/dev/null 2>&1; then
    log "执行 kubeadm reset"
    kubeadm reset -f >/dev/null 2>&1 || true
  fi

  log "清理 Kubernetes 运行目录"
  rm -rf /etc/kubernetes
  rm -rf /var/lib/kubelet
  rm -rf /var/lib/cni
  rm -rf /etc/cni/net.d
  rm -rf /var/lib/etcd
}

reset_machine_identity() {
  log "重置 machine-id"
  rm -f /etc/machine-id
  systemd-machine-id-setup >/dev/null
}

cleanup_optional_state() {
  if [ "${REMOVE_NETWORK_CONFIG:-0}" = "1" ]; then
    log "按参数要求清理网络配置"
    rm -f /etc/netplan/*.yaml >/dev/null 2>&1 || true
  fi
}

print_next_steps() {
  cat <<'EOF'
完成后请手工检查并修改：
- 主机名：hostnamectl set-hostname <new-name>
- IP 地址：按你的虚拟机平台重新设置
- /etc/hosts：确保新主机名和 IP 正确
- 如果这台机器要作为 worker，再重新执行 kubeadm join
EOF
}

require_root
stop_kubernetes_services
reset_kubernetes_state
reset_machine_identity
cleanup_optional_state
print_next_steps

