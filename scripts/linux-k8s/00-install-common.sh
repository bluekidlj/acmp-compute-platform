#!/bin/sh
set -eu

# ACMP Demo 节点公共安装脚本。
# 支持 Ubuntu 22.04/24.04 x86_64，固定 containerd 1.7.27 和 Kubernetes 1.28.15。
# 说明：本脚本默认不再修改系统源，使用你当前已经配置好的源。

KUBERNETES_VERSION="${KUBERNETES_VERSION:-1.28.15}"
CONTAINERD_VERSION="${CONTAINERD_VERSION:-1.7.27}"
RUNC_VERSION="${RUNC_VERSION:-1.2.6}"
CNI_PLUGINS_VERSION="${CNI_PLUGINS_VERSION:-1.6.2}"
CRICTL_VERSION="${CRICTL_VERSION:-1.28.0}"
GITHUB_PROXY="${GITHUB_PROXY:-}"
K8S_APT_MIRROR="${K8S_APT_MIRROR:-https://pkgs.k8s.io/core:/stable:/v1.28/deb}"
K8S_IMAGE_REPOSITORY="${K8S_IMAGE_REPOSITORY:-registry.aliyuncs.com/google_containers}"
NODE_NAME="${NODE_NAME:-}"

log() {
  printf '\n[%s] %s\n' "$(date '+%F %T')" "$*"
}

fail() {
  echo "错误: $*" >&2
  exit 1
}

github_url() {
  printf '%s%s' "${GITHUB_PROXY}" "$1"
}

require_root() {
  [ "${EUID:-$(id -u)}" -eq 0 ] || fail "请使用 sudo 运行此脚本"
  [ "$(uname -m)" = "x86_64" ] || fail "当前脚本只支持 x86_64"
  [ -f /etc/os-release ] || fail "无法识别 Linux 发行版"
  # shellcheck disable=SC1091
  . /etc/os-release
  [ "${ID}" = "ubuntu" ] || fail "当前脚本只支持 Ubuntu 22.04/24.04"
  [ "${VERSION_ID}" = "22.04" ] || [ "${VERSION_ID}" = "24.04" ] \
    || fail "仅验证 Ubuntu 22.04/24.04，当前为 ${VERSION_ID}"
}

configure_hostname() {
  if [ -n "${NODE_NAME}" ]; then
    log "设置节点名为 ${NODE_NAME}"
    hostnamectl set-hostname "${NODE_NAME}"
  fi
}

install_base_packages() {
  log "安装基础软件"
  apt-get update
  DEBIAN_FRONTEND=noninteractive apt-get install -y \
    apt-transport-https ca-certificates curl gpg jq socat conntrack \
    ipset ipvsadm ethtool ebtables bash-completion
}

configure_kernel() {
  log "关闭 swap 并配置 Kubernetes 内核参数"
  swapoff -a
  sed -ri '/\sswap\s/s/^#?/#/' /etc/fstab

  cat >/etc/modules-load.d/acmp-k8s.conf <<'EOF'
overlay
br_netfilter
EOF
  modprobe overlay
  modprobe br_netfilter

  cat >/etc/sysctl.d/99-acmp-k8s.conf <<'EOF'
net.bridge.bridge-nf-call-iptables = 1
net.bridge.bridge-nf-call-ip6tables = 1
net.ipv4.ip_forward = 1
EOF
  sysctl --system >/dev/null
}

install_containerd() {
  log "安装 containerd ${CONTAINERD_VERSION}"
  archive="/tmp/containerd-${CONTAINERD_VERSION}-linux-amd64.tar.gz"
  curl -fL --retry 5 --retry-delay 3 \
    "$(github_url "https://github.com/containerd/containerd/releases/download/v${CONTAINERD_VERSION}/containerd-${CONTAINERD_VERSION}-linux-amd64.tar.gz")" \
    -o "${archive}"
  tar -C /usr/local -xzf "${archive}"

  curl -fL --retry 5 --retry-delay 3 \
    "$(github_url "https://github.com/opencontainers/runc/releases/download/v${RUNC_VERSION}/runc.amd64")" \
    -o /usr/local/sbin/runc
  chmod 0755 /usr/local/sbin/runc

  mkdir -p /opt/cni/bin
  curl -fL --retry 5 --retry-delay 3 \
    "$(github_url "https://github.com/containernetworking/plugins/releases/download/v${CNI_PLUGINS_VERSION}/cni-plugins-linux-amd64-v${CNI_PLUGINS_VERSION}.tgz")" \
    -o /tmp/cni-plugins.tgz
  tar -C /opt/cni/bin -xzf /tmp/cni-plugins.tgz

  curl -fL --retry 5 --retry-delay 3 \
    "$(github_url "https://raw.githubusercontent.com/containerd/containerd/v${CONTAINERD_VERSION}/containerd.service")" \
    -o /etc/systemd/system/containerd.service

  mkdir -p /etc/containerd
  /usr/local/bin/containerd config default >/etc/containerd/config.toml
  sed -ri 's/SystemdCgroup = false/SystemdCgroup = true/' /etc/containerd/config.toml
  sed -ri \
    "s#sandbox_image = \"registry.k8s.io/pause:[^\"]+\"#sandbox_image = \"${K8S_IMAGE_REPOSITORY}/pause:3.9\"#" \
    /etc/containerd/config.toml

  systemctl daemon-reload
  systemctl enable --now containerd
  systemctl restart containerd
}

install_kubernetes() {
  log "配置 Kubernetes v1.28 软件源"
  mkdir -p /etc/apt/keyrings
  curl -fsSL "${K8S_APT_MIRROR}/Release.key" \
    | gpg --dearmor --yes -o /etc/apt/keyrings/kubernetes-apt-keyring.gpg
  cat >/etc/apt/sources.list.d/kubernetes.list <<EOF
deb [signed-by=/etc/apt/keyrings/kubernetes-apt-keyring.gpg] ${K8S_APT_MIRROR}/ /
EOF
  apt-get update

  local package_version
  package_version="$(apt-cache madison kubeadm \
    | awk -v version="${KUBERNETES_VERSION}" '$3 ~ ("^" version "-") {print $3; exit}')"
  [ -n "${package_version}" ] \
    || fail "镜像源中没有 Kubernetes ${KUBERNETES_VERSION}，请检查 K8S_APT_MIRROR"

  apt-mark unhold kubelet kubeadm kubectl >/dev/null 2>&1 || true
  DEBIAN_FRONTEND=noninteractive apt-get install -y \
    "kubelet=${package_version}" \
    "kubeadm=${package_version}" \
    "kubectl=${package_version}"
  apt-mark hold kubelet kubeadm kubectl
  systemctl enable kubelet

  log "安装 crictl ${CRICTL_VERSION}"
  curl -fL --retry 5 --retry-delay 3 \
    "$(github_url "https://github.com/kubernetes-sigs/cri-tools/releases/download/v${CRICTL_VERSION}/crictl-v${CRICTL_VERSION}-linux-amd64.tar.gz")" \
    -o /tmp/crictl.tar.gz
  tar -C /usr/local/bin -xzf /tmp/crictl.tar.gz
  cat >/etc/crictl.yaml <<'EOF'
runtime-endpoint: unix:///run/containerd/containerd.sock
image-endpoint: unix:///run/containerd/containerd.sock
timeout: 20
debug: false
EOF
}

verify_installation() {
  log "验证公共组件版本"
  containerd --version
  runc --version | head -n 1
  kubeadm version -o short
  kubelet --version
  kubectl version --client=true
  crictl version
  systemctl is-active --quiet containerd || fail "containerd 未运行"
  log "公共组件安装完成"
}

require_root
configure_hostname
install_base_packages
configure_kernel
install_containerd
install_kubernetes
verify_installation
