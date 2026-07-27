[CmdletBinding()]
param(
    [ValidateSet('All', 'Backend', 'Frontend')]
    [string]$Target,

    [ValidateSet('Preview', 'Dev')]
    [string]$FrontendMode = 'Preview',

    [switch]$ForceInstall,
    [switch]$SkipBuild,
    [int]$BackendStartupTimeoutSeconds = 30,
    [int]$FrontendStartupTimeoutSeconds = 30
)

$ErrorActionPreference = 'Stop'

$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$runtimeDir = Join-Path $projectRoot '.runtime'
$backendPidFile = Join-Path $runtimeDir 'backend.pid'
$frontendPidFile = Join-Path $runtimeDir 'frontend.pid'
$backendOutLog = Join-Path $runtimeDir 'backend.out.log'
$backendErrLog = Join-Path $runtimeDir 'backend.err.log'
$frontendOutLog = Join-Path $runtimeDir 'frontend.out.log'
$frontendErrLog = Join-Path $runtimeDir 'frontend.err.log'
$frontendBuildLog = Join-Path $runtimeDir 'frontend.build.log'
$frontendDir = Join-Path $projectRoot 'frontend'
$backendRestartScript = Join-Path $projectRoot 'scripts\restart-backend.ps1'

function Write-Log {
    param(
        [Parameter(Mandatory)][string]$Level,
        [Parameter(Mandatory)][string]$Message
    )

    Write-Host ("[{0}] [{1}] {2}" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $Level.ToUpperInvariant(), $Message)
}

function Resolve-CommandPath {
    param([Parameter(Mandatory)][string]$Name)

    $command = Get-Command $Name -ErrorAction SilentlyContinue
    if ($null -eq $command) {
        throw "Required command '$Name' was not found in PATH."
    }
    return $command.Source
}

function Test-ProcessAlive {
    param([Parameter(Mandatory)][int]$Pid)

    try {
        return $null -ne (Get-Process -Id $Pid -ErrorAction Stop)
    }
    catch {
        return $false
    }
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
    if (-not [int]::TryParse($raw, [ref]$savedPid)) {
        Remove-Item -LiteralPath $PidFile -Force -ErrorAction SilentlyContinue
        return
    }

    if (Test-ProcessAlive -Pid $savedPid) {
        Write-Log 'INFO' "Stopping old $Name process (PID $savedPid)"
        Stop-Process -Id $savedPid -Force -ErrorAction SilentlyContinue
        Start-Sleep -Seconds 1
    }

    Remove-Item -LiteralPath $PidFile -Force -ErrorAction SilentlyContinue
}

function Read-MenuChoice {
    Write-Host ''
    Write-Host '请选择启动范围:'
    Write-Host '  1) 全部（后端 + 前端，默认）'
    Write-Host '  2) 仅后端'
    Write-Host '  3) 仅前端'
    $choice = Read-Host '输入编号后回车'

    switch ($choice) {
        '2' { return 'Backend' }
        '3' { return 'Frontend' }
        default { return 'All' }
    }
}

function Start-Backend {
    Write-Log 'INFO' '准备重启后端'
    if (-not (Test-Path -LiteralPath $backendRestartScript)) {
        throw "Backend restart script not found: $backendRestartScript"
    }

    $args = @('-ExecutionPolicy', 'Bypass', '-File', $backendRestartScript)
    if ($BackendStartupTimeoutSeconds -gt 0) {
        $args += @('-StartupTimeoutSeconds', $BackendStartupTimeoutSeconds)
    }

    & powershell @args
    if ($LASTEXITCODE -ne 0) {
        throw "Backend restart failed with exit code $LASTEXITCODE."
    }

    Write-Log 'INFO' "后端日志: $backendOutLog / $backendErrLog"
}

function Start-Frontend {
    $npm = Resolve-CommandPath 'npm'
    $node = Resolve-CommandPath 'node'

    Write-Log 'INFO' "Node: $(& $node --version)"
    Write-Log 'INFO' "npm: $(& $npm --version)"

    $nodeModules = Join-Path $frontendDir 'node_modules'
    $needInstall = $ForceInstall -or (-not (Test-Path -LiteralPath $nodeModules))

    if ($needInstall) {
        Write-Log 'INFO' '安装前端依赖'
        Push-Location $frontendDir
        try {
            & $npm install *>> $frontendBuildLog
            if ($LASTEXITCODE -ne 0) {
                throw "npm install failed with exit code $LASTEXITCODE. See $frontendBuildLog"
            }
        }
        finally {
            Pop-Location
        }
    }

    if (-not $SkipBuild) {
        Write-Log 'INFO' '重新构建前端'
        Push-Location $frontendDir
        try {
            & $npm run build *>> $frontendBuildLog
            if ($LASTEXITCODE -ne 0) {
                throw "npm run build failed with exit code $LASTEXITCODE. See $frontendBuildLog"
            }
        }
        finally {
            Pop-Location
        }
    }

    Stop-ProcessByPidFile -PidFile $frontendPidFile -Name 'frontend'

    $startArgs = if ($FrontendMode -eq 'Dev') {
        @('run', 'dev', '--', '--host', '0.0.0.0', '--port', '3000')
    }
    else {
        @('run', 'preview', '--', '--host', '0.0.0.0', '--port', '3000')
    }

    Write-Log 'INFO' "启动前端 ($FrontendMode)"
    $proc = Start-Process `
        -FilePath $npm `
        -ArgumentList $startArgs `
        -WorkingDirectory $frontendDir `
        -WindowStyle Hidden `
        -RedirectStandardOutput $frontendOutLog `
        -RedirectStandardError $frontendErrLog `
        -PassThru

    Set-Content -LiteralPath $frontendPidFile -Value $proc.Id
    $deadline = (Get-Date).AddSeconds($FrontendStartupTimeoutSeconds)
    do {
        Start-Sleep -Milliseconds 500
        if ($proc.HasExited) {
            Write-Log 'ERROR' "前端进程退出，ExitCode=$($proc.ExitCode)"
            if (Test-Path -LiteralPath $frontendErrLog) {
                Get-Content -LiteralPath $frontendErrLog -Tail 50
            }
            throw 'Frontend failed to start.'
        }
    } while ((Get-Date) -lt $deadline)

    Write-Log 'INFO' "前端已启动，PID=$($proc.Id)"
    Write-Log 'INFO' "前端页面: http://127.0.0.1:3000/"
    Write-Log 'INFO' "前端日志: $frontendOutLog / $frontendErrLog"
}

New-Item -ItemType Directory -Force -Path $runtimeDir | Out-Null

if (-not $Target) {
    $Target = Read-MenuChoice
}

switch ($Target) {
    'Backend' {
        Start-Backend
    }
    'Frontend' {
        Start-Frontend
    }
    default {
        Start-Backend
        Start-Frontend
    }
}
