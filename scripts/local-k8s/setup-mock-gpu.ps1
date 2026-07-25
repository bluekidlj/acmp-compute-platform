param(
    [string]$NodeName = "desktop-control-plane",
    [int]$GpuCount = 8,
    [string]$GpuModel = "NVIDIA-A100-SXM4-80GB"
)

$ErrorActionPreference = "Stop"

kubectl get node $NodeName | Out-Null
kubectl label node $NodeName "nvidia.com/gpu.product=$GpuModel" --overwrite | Out-Null

$patch = @{
    status = @{
        capacity = @{
            "nvidia.com/gpu" = [string]$GpuCount
        }
    }
} | ConvertTo-Json -Depth 4 -Compress

kubectl patch node $NodeName `
    --subresource=status `
    --type=merge `
    --patch $patch | Out-Null

$found = $false
for ($attempt = 1; $attempt -le 15; $attempt++) {
    $allocatableGpu = kubectl get node $NodeName `
        -o jsonpath="{.status.allocatable.nvidia\.com/gpu}"

    if ($allocatableGpu -eq [string]$GpuCount) {
        $found = $true
        break
    }

    Start-Sleep -Seconds 2
}

if (-not $found) {
    throw "Node allocatable 中未出现模拟 GPU，请检查 kubelet 状态更新。"
}

kubectl get node $NodeName `
    -o jsonpath="node={.metadata.name} model={.metadata.labels.nvidia\.com/gpu\.product} capacityGpu={.status.capacity.nvidia\.com/gpu} allocatableGpu={.status.allocatable.nvidia\.com/gpu}{'\n'}"
