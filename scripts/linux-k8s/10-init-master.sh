#!/usr/bin/env bash
set -Eeuo pipefail

# 在 Master 节点执行。默认先调用公共安装脚本，再初始化控制面和 Flannel。

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
KUBERNETES_VERSION="${KUBERNETES_VERSION:-1.28.15}"
K8S_IMAGE_REPOSITORY="${K8S_IMAGE_REPOSITORY:-registry.aliyuncs.com/google_containers}"
POD_CIDR="${POD_CIDR:-10.244.0.0/16}"
SERVICE_CIDR="${SERVICE_CIDR:-10.96.0.0/12}"
APISERVER_ADVERTISE_ADDRESS="${APISERVER_ADVERTISE_ADDRESS:-}"
FLANNEL_VERSION="${FLANNEL_VERSION:-v0.25.7}"
GITHUB_PROXY="${GITHUB_PROXY:-}"

[[ "${EUID}" -eq 0 ]] || { echo "请使用 sudo 运行"; exit 1; }
[[ "${POD_CIDR}" == "10.244.0.0/16" ]] || {
  echo "当前 Flannel Demo 脚本固定 POD_CIDR=10.244.0.0/16" >&2
  exit 1
}

if [[ "${SKIP_COMMON_INSTALL:-0}" != "1" ]]; then
  "${SCRIPT_DIR}/00-install-common.sh"
fi

if [[ -z "${APISERVER_ADVERTISE_ADDRESS}" ]]; then
  APISERVER_ADVERTISE_ADDRESS="$(ip route get 1.1.1.1 \
    | awk '{for (i=1;i<=NF;i++) if ($i=="src") {print $(i+1); exit}}')"
fi
[[ -n "${APISERVER_ADVERTISE_ADDRESS}" ]] || {
  echo "无法自动识别 Master IP，请设置 APISERVER_ADVERTISE_ADDRESS" >&2
  exit 1
}

echo "预拉取 Kubernetes ${KUBERNETES_VERSION} 控制面镜像"
kubeadm config images pull \
  --kubernetes-version "v${KUBERNETES_VERSION}" \
  --image-repository "${K8S_IMAGE_REPOSITORY}" \
  --cri-socket unix:///run/containerd/containerd.sock

if [[ ! -f /etc/kubernetes/admin.conf ]]; then
  kubeadm init \
    --kubernetes-version "v${KUBERNETES_VERSION}" \
    --image-repository "${K8S_IMAGE_REPOSITORY}" \
    --apiserver-advertise-address "${APISERVER_ADVERTISE_ADDRESS}" \
    --pod-network-cidr "${POD_CIDR}" \
    --service-cidr "${SERVICE_CIDR}" \
    --cri-socket unix:///run/containerd/containerd.sock
else
  echo "检测到 /etc/kubernetes/admin.conf，跳过 kubeadm init"
fi

install -d -m 0700 /root/.kube
install -m 0600 /etc/kubernetes/admin.conf /root/.kube/config

echo "安装 Flannel ${FLANNEL_VERSION}"
FLANNEL_MANIFEST="/tmp/kube-flannel.yml"
curl -fL --retry 5 --retry-delay 3 \
  "${GITHUB_PROXY}https://github.com/flannel-io/flannel/releases/download/${FLANNEL_VERSION}/kube-flannel.yml" \
  -o "${FLANNEL_MANIFEST}"
kubectl apply -f "${FLANNEL_MANIFEST}"

echo "等待控制面和 Flannel"
kubectl wait --for=condition=Ready node "$(hostname)" --timeout=300s || true
kubectl rollout status daemonset/kube-flannel-ds -n kube-flannel --timeout=300s

JOIN_COMMAND="$(kubeadm token create --ttl 0 --print-join-command)"
cat >/root/acmp-worker-join.sh <<EOF
#!/usr/bin/env bash
set -Eeuo pipefail
${JOIN_COMMAND} --cri-socket unix:///run/containerd/containerd.sock
EOF
chmod 0700 /root/acmp-worker-join.sh

echo
echo "Master 初始化完成。请把下面命令复制到 Worker 的 20-join-worker.sh："
echo "${JOIN_COMMAND}"
echo
echo "ACMP 注册集群使用的 kubeconfig：/etc/kubernetes/admin.conf"
kubectl get nodes -o wide
