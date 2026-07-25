$ErrorActionPreference = "Stop"

$namespace = "acmp-test-lifecycle"
$deployment = "acmp-test-inference"
$service = "acmp-test-inference-svc"
$port = 9000

try {
    kubectl delete namespace $namespace `
        --ignore-not-found `
        --wait=true `
        --timeout=60s | Out-Null

    kubectl create namespace $namespace | Out-Null
    kubectl create deployment $deployment `
        --namespace $namespace `
        --image=registry.k8s.io/pause:3.10 `
        --replicas=1 | Out-Null

    kubectl expose deployment $deployment `
        --namespace $namespace `
        --name=$service `
        --port=$port `
        --target-port=$port | Out-Null

    kubectl rollout status "deployment/$deployment" `
        --namespace $namespace `
        --timeout=60s

    $readyReplicas = kubectl get deployment $deployment `
        --namespace $namespace `
        -o jsonpath="{.status.readyReplicas}"
    $servicePort = kubectl get service $service `
        --namespace $namespace `
        -o jsonpath="{.spec.ports[0].port}"
    $targetPort = kubectl get service $service `
        --namespace $namespace `
        -o jsonpath="{.spec.ports[0].targetPort}"

    if ($readyReplicas -ne "1") {
        throw "Deployment 未达到 1 个 Ready 副本。"
    }
    if ($servicePort -ne [string]$port -or $targetPort -ne [string]$port) {
        throw "Service 端口不正确。"
    }

    Write-Output "K8S_LIFECYCLE_OK readyReplicas=$readyReplicas port=$servicePort"
} finally {
    kubectl delete namespace $namespace `
        --ignore-not-found `
        --wait=true `
        --timeout=60s | Out-Null
}

