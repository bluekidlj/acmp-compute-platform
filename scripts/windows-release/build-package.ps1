[CmdletBinding()]
param(
    [switch]$SkipFrontendBuild,
    [switch]$SkipBackendBuild
)

$ErrorActionPreference = 'Stop'

$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$frontendRoot = Join-Path $projectRoot 'frontend'
$templateRoot = Join-Path $PSScriptRoot 'templates'
$stageRoot = Join-Path $projectRoot '.runtime\release-staging'
$mavenTarget = Join-Path $projectRoot '.runtime\windows-release-target'
$frontendBuildRoot = Join-Path $projectRoot '.runtime\windows-release-frontend'
$legacyStageRoot = Join-Path $projectRoot '.runtime\windows-release'
$outputRoot = Join-Path $projectRoot 'release'
$buildId = Get-Date -Format 'yyyyMMdd-HHmmss'
$releaseName = "acmp-release-$buildId"
$releaseRoot = Join-Path $stageRoot $releaseName
$archivePath = Join-Path $OutputRoot "$releaseName.tar.gz"

function Write-Step {
    param([string]$Message)
    Write-Host ("[{0}] {1}" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $Message)
}

function Require-Command {
    param([string]$Name)
    $command = Get-Command $Name -ErrorAction SilentlyContinue
    if ($null -eq $command) {
        throw "Required command '$Name' was not found in PATH."
    }
    return $command.Source
}

function Copy-TextAsLinuxFile {
    param(
        [string]$Source,
        [string]$Destination
    )
    if (-not (Test-Path -LiteralPath $Source -PathType Leaf)) {
        throw "Missing release template: $Source"
    }
    $content = [System.IO.File]::ReadAllText($Source).Replace("`r`n", "`n")
    $encoding = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($Destination, $content, $encoding)
}

function Remove-ReleaseIntermediates {
    foreach ($path in @($stageRoot, $mavenTarget, $frontendBuildRoot, $legacyStageRoot)) {
        if (Test-Path -LiteralPath $path) {
            Remove-Item -LiteralPath $path -Recurse -Force -ErrorAction SilentlyContinue
        }
    }
    Get-ChildItem -LiteralPath (Join-Path $projectRoot '.runtime') -Directory `
            -Filter 'release-check-*' -ErrorAction SilentlyContinue |
        Remove-Item -Recurse -Force -ErrorAction SilentlyContinue
}

try {
    $maven = Require-Command 'mvn'
    $npm = Require-Command 'npm'
    $tar = Require-Command 'tar'

if (-not $SkipBackendBuild) {
    Write-Step '[1/6] Building backend JAR'
    Push-Location $projectRoot
    try {
        & $maven clean package -DskipTests -Pwindows-release
        if ($LASTEXITCODE -ne 0) {
            throw "Maven build failed with exit code $LASTEXITCODE."
        }
    }
    finally {
        Pop-Location
    }
}

$backendJar = Get-ChildItem -LiteralPath $mavenTarget -File -Filter '*.jar' |
    Where-Object { $_.Name -notlike '*.original' -and $_.Name -notlike 'original-*' } |
    Sort-Object LastWriteTime |
    Select-Object -Last 1
if ($null -eq $backendJar) {
    throw 'Backend JAR was not found in the release build directory. Run without -SkipBackendBuild.'
}

if (-not $SkipFrontendBuild) {
    Write-Step '[2/6] Building frontend assets in an isolated workspace'
    if (Test-Path -LiteralPath $frontendBuildRoot) {
        Remove-Item -LiteralPath $frontendBuildRoot -Recurse -Force
    }
    New-Item -ItemType Directory -Force -Path $frontendBuildRoot | Out-Null
    foreach ($itemName in @('src', 'public', 'index.html', 'package.json', 'package-lock.json', 'tsconfig.json', 'vite.config.ts')) {
        $sourceItem = Join-Path $frontendRoot $itemName
        if (Test-Path -LiteralPath $sourceItem) {
            Copy-Item -LiteralPath $sourceItem -Destination $frontendBuildRoot -Recurse -Force
        }
    }
    Push-Location $frontendBuildRoot
    try {
        if (Test-Path -LiteralPath (Join-Path $frontendBuildRoot 'package-lock.json')) {
            & $npm ci --no-audit --no-fund
        }
        else {
            & $npm install --no-audit --no-fund
        }
        if ($LASTEXITCODE -ne 0) {
            throw "npm install failed with exit code $LASTEXITCODE."
        }
        & $npm run build
        if ($LASTEXITCODE -ne 0) {
            throw "Frontend build failed with exit code $LASTEXITCODE."
        }
    }
    finally {
        Pop-Location
    }
}

$frontendDist = if ($SkipFrontendBuild) {
    Join-Path $frontendRoot 'dist'
}
else {
    Join-Path $frontendBuildRoot 'dist'
}
if (-not (Test-Path -LiteralPath (Join-Path $frontendDist 'index.html') -PathType Leaf)) {
    throw 'Frontend dist/index.html was not found. Run without -SkipFrontendBuild.'
}

Write-Step '[3/6] Creating release directory'
if (Test-Path -LiteralPath $releaseRoot) {
    Remove-Item -LiteralPath $releaseRoot -Recurse -Force
}
$frontEndTarget = Join-Path $releaseRoot 'front-end'
$backEndTarget = Join-Path $releaseRoot 'back-end'
$backEndConfTarget = Join-Path $backEndTarget 'conf'
New-Item -ItemType Directory -Force -Path $frontEndTarget | Out-Null
New-Item -ItemType Directory -Force -Path $backEndConfTarget | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $backEndTarget 'log') | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $backEndTarget 'data') | Out-Null

Copy-Item -Path (Join-Path $frontendDist '*') -Destination $frontEndTarget -Recurse -Force
Copy-Item -LiteralPath $backendJar.FullName -Destination (Join-Path $backEndTarget 'acmp-compute.jar') -Force
Copy-Item -LiteralPath (Join-Path $projectRoot 'src\main\resources\application.yml') -Destination $backEndConfTarget -Force
Copy-Item -LiteralPath (Join-Path $projectRoot 'src\main\resources\logback-spring.xml') -Destination $backEndConfTarget -Force

Write-Step '[4/6] Adding Linux start/stop scripts and Nginx config'
foreach ($fileName in @('start-back.sh', 'start-front.sh', 'stop.sh', 'nginx.conf')) {
    Copy-TextAsLinuxFile -Source (Join-Path $templateRoot $fileName) -Destination (Join-Path $releaseRoot $fileName)
}

Write-Step '[5/6] Creating release archive'
New-Item -ItemType Directory -Force -Path $OutputRoot | Out-Null
Get-ChildItem -LiteralPath $OutputRoot -File -Filter 'acmp-release-*.tar.gz' -ErrorAction SilentlyContinue |
    Remove-Item -Force
if (Test-Path -LiteralPath $archivePath) {
    Remove-Item -LiteralPath $archivePath -Force
}
Push-Location $stageRoot
try {
    & $tar -czf $archivePath $releaseName
    if ($LASTEXITCODE -ne 0) {
        throw "tar failed with exit code $LASTEXITCODE."
    }
}
finally {
    Pop-Location
}

Write-Step '[6/6] Verifying archive'
$archiveEntries = & $tar -tzf $archivePath
$hasFrontend = $archiveEntries -contains "$releaseName/front-end/index.html"
$hasBackend = $archiveEntries -contains "$releaseName/back-end/acmp-compute.jar"
$hasNginxConfig = $archiveEntries -contains "$releaseName/nginx.conf"
if ($LASTEXITCODE -ne 0 -or -not $hasFrontend -or -not $hasBackend -or -not $hasNginxConfig) {
    throw "Release archive verification failed: $archivePath"
}

Write-Step "Release ready: $archivePath"
}
finally {
    Remove-ReleaseIntermediates
}
