[CmdletBinding()]
param(
    [string]$OutputRoot = (Join-Path (Split-Path -Parent $PSScriptRoot) 'release'),
    [switch]$SkipFrontendBuild,
    [switch]$SkipBackendBuild
)

$ErrorActionPreference = 'Stop'

$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$frontendDir = Join-Path $projectRoot 'frontend'
$backendDir = $projectRoot
$sourceAppYaml = Join-Path $projectRoot 'src\main\resources\application.yml'
$sourceLogback = Join-Path $projectRoot 'src\main\resources\logback-spring.xml'

$buildId = Get-Date -Format 'yyyyMMdd-HHmmss'
$stageRoot = Join-Path $projectRoot ".runtime\windows-release"
$packageDir = Join-Path $stageRoot "acmp-$buildId"
$artifactName = "acmp-$buildId.tar.gz"
$artifactPath = Join-Path $OutputRoot $artifactName

function Write-Log {
    param(
        [Parameter(Mandatory)][string]$Level,
        [Parameter(Mandatory)][string]$Message
    )

    Write-Host ("[{0}] [{1}] {2}" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $Level.ToUpperInvariant(), $Message)
}

function Require-Command {
    param([Parameter(Mandatory)][string]$Name)

    $command = Get-Command $Name -ErrorAction SilentlyContinue
    if ($null -eq $command) {
        throw "Required command '$Name' was not found in PATH."
    }
    return $command.Source
}

function Copy-IfExists {
    param(
        [Parameter(Mandatory)][string]$Source,
        [Parameter(Mandatory)][string]$Target
    )

    if (Test-Path -LiteralPath $Source) {
        Copy-Item -LiteralPath $Source -Destination $Target -Force
    }
}

function New-ReleaseStartScript {
    param([Parameter(Mandatory)][string]$TargetDir)

    $content = @'
#!/usr/bin/env bash
set -Eeuo pipefail

APP_HOME="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR_PATH="${JAR_PATH:-${APP_HOME}/app.jar}"
CONF_DIR="${CONF_DIR:-${APP_HOME}}"
LOG_DIR="${LOG_DIR:-${APP_HOME}/log}"
PID_FILE="${PID_FILE:-${APP_HOME}/acmp-backend.pid}"
JAVA_BIN="${JAVA_BIN:-java}"
JAVA_OPTS="${JAVA_OPTS:--Xms512m -Xmx512m -Dfile.encoding=UTF-8}"

mkdir -p "${LOG_DIR}"

if [[ ! -f "${JAR_PATH}" ]]; then
  echo "missing jar: ${JAR_PATH}" >&2
  exit 1
fi

if [[ ! -f "${CONF_DIR}/application.yaml" ]]; then
  echo "missing config: ${CONF_DIR}/application.yaml" >&2
  exit 1
fi

if [[ -f "${PID_FILE}" ]]; then
  OLD_PID="$(cat "${PID_FILE}" || true)"
  if [[ -n "${OLD_PID}" ]] && kill -0 "${OLD_PID}" 2>/dev/null; then
    echo "backend already running: ${OLD_PID}"
    exit 0
  fi
  rm -f "${PID_FILE}"
fi

nohup "${JAVA_BIN}" ${JAVA_OPTS} \
  -jar "${JAR_PATH}" \
  --spring.config.additional-location=file:"${CONF_DIR}/" \
  --logging.config=file:"${CONF_DIR}/logback-spring.xml" \
  >>"${LOG_DIR}/backend.bootstrap.log" 2>&1 &

echo $! > "${PID_FILE}"
echo "backend started: $(cat "${PID_FILE}")"
'@

    Set-Content -LiteralPath (Join-Path $TargetDir 'start.sh') -Value $content -Encoding ascii
}

function New-ReleaseStopScript {
    param([Parameter(Mandatory)][string]$TargetDir)

    $content = @'
#!/usr/bin/env bash
set -Eeuo pipefail

APP_HOME="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PID_FILE="${PID_FILE:-${APP_HOME}/acmp-backend.pid}"

if [[ -f "${PID_FILE}" ]]; then
  PID="$(cat "${PID_FILE}" || true)"
  if [[ -n "${PID}" ]] && kill -0 "${PID}" 2>/dev/null; then
    kill "${PID}"
    echo "stopped: ${PID}"
  fi
  rm -f "${PID_FILE}"
fi
'@

    Set-Content -LiteralPath (Join-Path $TargetDir 'stop.sh') -Value $content -Encoding ascii
}

Require-Command 'mvn'
Require-Command 'npm'
Require-Command 'tar'

New-Item -ItemType Directory -Force -Path $OutputRoot | Out-Null
Remove-Item -LiteralPath $packageDir -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $packageDir | Out-Null

if (-not $SkipBackendBuild) {
    Write-Log 'INFO' '构建后端 Jar'
    Push-Location $backendDir
    try {
        & mvn -DskipTests clean package
        if ($LASTEXITCODE -ne 0) {
            throw "mvn build failed with exit code $LASTEXITCODE"
        }
    }
    finally {
        Pop-Location
    }
}

$backendJar = Get-ChildItem -Path (Join-Path $backendDir 'target') -File -Filter '*.jar' |
    Where-Object { $_.Name -notlike '*original*' } |
    Sort-Object LastWriteTime |
    Select-Object -Last 1

if ($null -eq $backendJar) {
    throw '未找到后端 Jar，请先确认 Maven 构建成功。'
}

if (-not $SkipFrontendBuild) {
    Write-Log 'INFO' '构建前端'
    Push-Location $frontendDir
    try {
        & npm install
        if ($LASTEXITCODE -ne 0) {
            throw "npm install failed with exit code $LASTEXITCODE"
        }

        & npm run build
        if ($LASTEXITCODE -ne 0) {
            throw "npm run build failed with exit code $LASTEXITCODE"
        }
    }
    finally {
        Pop-Location
    }
}

$frontendDist = Join-Path $frontendDir 'dist'
if (-not (Test-Path -LiteralPath $frontendDist)) {
    throw "前端 dist 目录不存在: $frontendDist"
}

Write-Log 'INFO' '组装 release 目录'
New-Item -ItemType Directory -Force -Path $packageDir | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $packageDir 'log') | Out-Null

Copy-Item -LiteralPath $backendJar.FullName -Destination (Join-Path $packageDir 'app.jar') -Force
Copy-IfExists -Source $sourceAppYaml -Target (Join-Path $packageDir 'application.yaml')
Copy-IfExists -Source $sourceLogback -Target (Join-Path $packageDir 'logback-spring.xml')
Copy-Item -Path (Join-Path $frontendDist '*') -Destination $packageDir -Recurse -Force

if (-not (Test-Path -LiteralPath (Join-Path $packageDir 'application.yaml'))) {
    throw "未找到 application.yaml 源文件: $sourceAppYaml"
}

New-ReleaseStartScript -TargetDir $packageDir
New-ReleaseStopScript -TargetDir $packageDir

$readme = @"
ACMP Windows Release Package

目录结构要求:
  app.jar
  application.yaml
  logback-spring.xml
  start.sh
  stop.sh
  log/
  前端静态文件

启动:
  ./start.sh

停止:
  ./stop.sh

说明:
  1. application.yaml 与 app.jar 同级。
  2. 启动脚本会自动把外置配置目录指向当前目录。
  3. 如果后端日志需要单独目录，按实际需要调整 application.yaml 里的 logging 配置。
"@
Set-Content -LiteralPath (Join-Path $packageDir 'README.txt') -Value $readme -Encoding ascii

$parentName = Split-Path $packageDir -Leaf
Write-Log 'INFO' "打包目录: $packageDir"
Write-Log 'INFO' "压缩文件: $artifactPath"

if (Test-Path -LiteralPath $artifactPath) {
    Remove-Item -LiteralPath $artifactPath -Force
}

Push-Location $stageRoot
try {
    & tar -czf $artifactPath $parentName
    if ($LASTEXITCODE -ne 0) {
        throw "tar package failed with exit code $LASTEXITCODE"
    }
}
finally {
    Pop-Location
}

Write-Log 'INFO' "完成: $artifactPath"
