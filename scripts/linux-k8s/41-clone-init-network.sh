#!/bin/sh
set -eu

# ACMP Demo 克隆后网络初始化脚本。
# 用途：在 VM 克隆后，通过控制台进入新机器，一次性修改主机名、IP 和 SSH 主机身份。
# 说明：此脚本不会自动猜测你的新 IP，必须显式传入参数。
#
# 用法：
#   sudo NEW_HOSTNAME=gpu-worker-02 \
#     NEW_ADDRESS=192.168.10.22/24 \
#     NEW_GATEWAY=192.168.10.1 \
#     NEW_DNS="8.8.8.8 114.114.114.114" \
#     ./41-clone-init-network.sh

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

detect_interface() {
  DEFAULT_IFACE="$(ip route show default 2>/dev/null | awk 'NR==1 {print $5}')"
  [ -n "${DEFAULT_IFACE:-}" ] || fail "无法自动识别默认网卡，请先确认系统已有默认路由"
}

write_netplan() {
  [ -n "${NEW_ADDRESS:-}" ] || fail "必须提供 NEW_ADDRESS，例如 192.168.10.22/24"

  if [ -n "${NEW_HOSTNAME:-}" ]; then
    log "设置主机名为 ${NEW_HOSTNAME}"
    hostnamectl set-hostname "${NEW_HOSTNAME}"
  fi

  detect_interface
  log "使用网卡 ${DEFAULT_IFACE} 生成新的 netplan 配置"

  cat >/etc/netplan/99-acmp-clone.yaml <<EOF
network:
  version: 2
  ethernets:
    ${DEFAULT_IFACE}:
      dhcp4: no
      addresses:
        - ${NEW_ADDRESS}
EOF

  if [ -n "${NEW_GATEWAY:-}" ]; then
    cat >>/etc/netplan/99-acmp-clone.yaml <<EOF
      routes:
        - to: default
          via: ${NEW_GATEWAY}
EOF
  fi

  if [ -n "${NEW_DNS:-}" ]; then
    cat >>/etc/netplan/99-acmp-clone.yaml <<EOF
      nameservers:
        addresses:
EOF
    for dns in ${NEW_DNS}; do
      printf '          - %s\n' "${dns}" >>/etc/netplan/99-acmp-clone.yaml
    done
  fi
}

reset_ssh_identity() {
  log "清理 SSH host key，避免克隆机和模板机指纹一样"
  rm -f /etc/ssh/ssh_host_*
  dpkg-reconfigure openssh-server >/dev/null 2>&1 || true
  systemctl restart ssh >/dev/null 2>&1 || systemctl restart sshd >/dev/null 2>&1 || true
}

apply_network() {
  log "应用 netplan"
  netplan generate
  netplan apply
}

print_next_steps() {
  cat <<'EOF'
完成后你可以：
- 用新 IP 直接 SSH 到克隆机
- 再执行 kubeadm join 加入集群

如果 SSH 还是连不上，优先检查：
- 你的虚拟化平台是否真的给了新 IP
- 防火墙是否放行 22 端口
- 新机器和模板机是否仍然在同一个二层网络里
EOF
}

require_root
write_netplan
reset_ssh_identity
apply_network
print_next_steps

