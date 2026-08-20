[CmdletBinding()]
param(
    [switch]$RunTests,
    [int]$StartupTimeoutSeconds = 30,
    [int]$BackendPort = 8080
)

$ErrorActionPreference = 'Stop'

$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$jarName = 'acmp-compute-1.0.0-SNAPSHOT.jar'
$jarPath = Join-Path $projectRoot "target\$jarName"
$runtimeDir = Join-Path $projectRoot '.runtime'
$pidFile = Join-Path $runtimeDir 'backend.pid'
$stdoutLog = Join-Path $runtimeDir 'backend.out.log'
$stderrLog = Join-Path $runtimeDir 'backend.err.log'

function Stop-OldBackend {
    $processIds = [System.Collections.Generic.HashSet[int]]::new()

    if (Test-Path -LiteralPath $pidFile) {
        $savedPid = 0
        if ([int]::TryParse((Get-Content -Raw -LiteralPath $pidFile).Trim(), [ref]$savedPid)) {
            $savedProcess = Get-Process -Id $savedPid -ErrorAction SilentlyContinue
            if ($null -ne $savedProcess) {
                [void]$processIds.Add($savedPid)
            }
        }
    }

    Get-CimInstance Win32_Process |
        Where-Object {
            $_.Name -in @('java.exe', 'javaw.exe') -and
            $_.CommandLine -like "*$jarName*"
        } |
        ForEach-Object {
            [void]$processIds.Add([int]$_.ProcessId)
        }

    foreach ($processId in $processIds) {
        Write-Host "Stopping old backend process (PID $processId)..."
        Stop-Process -Id $processId -Force -ErrorAction SilentlyContinue
        Wait-Process -Id $processId -Timeout 10 -ErrorAction SilentlyContinue
    }
}

function Resolve-CommandPath {
    param([Parameter(Mandatory)][string]$Name)

    $command = Get-Command $Name -ErrorAction SilentlyContinue
    if ($null -eq $command) {
        throw "Required command '$Name' was not found in PATH."
    }
    return $command.Source
}

New-Item -ItemType Directory -Force -Path $runtimeDir | Out-Null
Stop-OldBackend

$maven = Resolve-CommandPath 'mvn'
$java = Resolve-CommandPath 'java'
# Do not run `mvn clean` here. Local K8s verification may keep files such as
# target/port-forward.out.log open, and Maven Clean cannot delete a locked file
# on Windows. `package` still recompiles changed sources and replaces the JAR.
$mavenArgs = @('package')
if (-not $RunTests) {
    $mavenArgs += '-DskipTests'
}

Write-Host "Building backend in $projectRoot..."
Push-Location $projectRoot
try {
    & $maven @mavenArgs
    if ($LASTEXITCODE -ne 0) {
        throw "Maven build failed with exit code $LASTEXITCODE."
    }
}
finally {
    Pop-Location
}

if (-not (Test-Path -LiteralPath $jarPath)) {
    throw "Build completed but JAR was not found: $jarPath"
}

Write-Host "Starting backend on port $BackendPort..."
$backendProcess = Start-Process `
    -FilePath $java `
    -ArgumentList @('-jar', $jarPath, "--server.port=$BackendPort") `
    -WorkingDirectory $projectRoot `
    -WindowStyle Hidden `
    -RedirectStandardOutput $stdoutLog `
    -RedirectStandardError $stderrLog `
    -PassThru

Set-Content -LiteralPath $pidFile -Value $backendProcess.Id

$deadline = (Get-Date).AddSeconds($StartupTimeoutSeconds)
do {
    Start-Sleep -Milliseconds 500

    if ($backendProcess.HasExited) {
        Write-Host "Backend exited unexpectedly with code $($backendProcess.ExitCode)."
        if (Test-Path -LiteralPath $stderrLog) {
            Get-Content -LiteralPath $stderrLog -Tail 50
        }
        exit 1
    }

    $tcpClient = [System.Net.Sockets.TcpClient]::new()
    try {
        $tcpClient.Connect('127.0.0.1', $BackendPort)
        $portReady = $tcpClient.Connected
    }
    catch {
        $portReady = $false
    }
    finally {
        $tcpClient.Dispose()
    }

    if ($portReady) {
        Write-Host "Backend started successfully (PID $($backendProcess.Id))."
        Write-Host "API: http://127.0.0.1:$BackendPort/api/v1"
        Write-Host "stdout: $stdoutLog"
        Write-Host "stderr: $stderrLog"
        exit 0
    }
} while ((Get-Date) -lt $deadline)

Write-Host "Backend process is running, but port 8080 was not ready within $StartupTimeoutSeconds seconds."
Write-Host "Check logs: $stdoutLog and $stderrLog"
exit 1
