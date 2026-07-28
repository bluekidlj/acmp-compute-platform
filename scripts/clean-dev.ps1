[CmdletBinding()]
param(
    [switch]$CleanFrontend = $true,
    [switch]$CleanBackend = $true,
    [switch]$MavenClean = $true
)

$ErrorActionPreference = 'Stop'

$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$runtimeDir = Join-Path $projectRoot '.runtime'
$backendPidFile = Join-Path $runtimeDir 'backend.pid'
$frontendPidFile = Join-Path $runtimeDir 'frontend.pid'
$backendJarName = 'acmp-compute-1.0.0-SNAPSHOT.jar'

function Write-Log {
    param(
        [Parameter(Mandatory)][string]$Level,
        [Parameter(Mandatory)][string]$Message
    )

    Write-Host ("[{0}] [{1}] {2}" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $Level.ToUpperInvariant(), $Message)
}

function Stop-ProcessByPidFile {
    param(
        [Parameter(Mandatory)][string]$PidFile,
        [Parameter(Mandatory)][string]$Name
    )

    if (-not (Test-Path -LiteralPath $PidFile)) {
        return
    }

    $savedPid = 0
    $raw = (Get-Content -Raw -LiteralPath $PidFile).Trim()
    if ([int]::TryParse($raw, [ref]$savedPid)) {
        $proc = Get-Process -Id $savedPid -ErrorAction SilentlyContinue
        if ($null -ne $proc) {
            Write-Log 'INFO' "Stopping $Name from pid file: $savedPid"
            Stop-Process -Id $savedPid -Force -ErrorAction SilentlyContinue
        }
    }

    Remove-Item -LiteralPath $PidFile -Force -ErrorAction SilentlyContinue
}

function Stop-BackendByCommand {
    $targets = Get-CimInstance Win32_Process |
        Where-Object {
            $_.Name -in @('java.exe', 'javaw.exe') -and
            $_.CommandLine -like "*$backendJarName*"
        }

    foreach ($target in $targets) {
        Write-Log 'INFO' "Stopping backend process: PID=$($target.ProcessId)"
        Stop-Process -Id $target.ProcessId -Force -ErrorAction SilentlyContinue
    }
}

function Stop-FrontendByCommand {
    $targets = Get-CimInstance Win32_Process |
        Where-Object {
            $_.Name -in @('node.exe', 'npm.cmd', 'npm.exe', 'cmd.exe') -and
            ($_.CommandLine -like '*vite*' -or $_.CommandLine -like '*frontend*')
        }

    foreach ($target in $targets) {
        Write-Log 'INFO' "Stopping frontend process: PID=$($target.ProcessId) Name=$($target.Name)"
        Stop-Process -Id $target.ProcessId -Force -ErrorAction SilentlyContinue
    }
}

New-Item -ItemType Directory -Force -Path $runtimeDir | Out-Null

if ($CleanBackend) {
    Stop-ProcessByPidFile -PidFile $backendPidFile -Name 'backend'
    Stop-BackendByCommand
}

if ($CleanFrontend) {
    Stop-ProcessByPidFile -PidFile $frontendPidFile -Name 'frontend'
    Stop-FrontendByCommand
}

if ($MavenClean) {
    $mvn = Get-Command mvn -ErrorAction SilentlyContinue
    if ($null -eq $mvn) {
        throw "Required command 'mvn' was not found in PATH."
    }

    Write-Log 'INFO' "Running mvn clean in $projectRoot"
    Push-Location $projectRoot
    try {
        & $mvn.Source clean
        if ($LASTEXITCODE -ne 0) {
            throw "mvn clean failed with exit code $LASTEXITCODE."
        }
    }
    finally {
        Pop-Location
    }
}

Write-Log 'INFO' '清理完成'
