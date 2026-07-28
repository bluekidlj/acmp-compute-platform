[CmdletBinding()]
param(
    [switch]$RunTests,
    [switch]$SkipClean
)

$ErrorActionPreference = 'Stop'

$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$maven = Get-Command mvn -ErrorAction SilentlyContinue
if ($null -eq $maven) {
    throw "Required command 'mvn' was not found in PATH."
}

$mavenArgs = @()
if (-not $SkipClean) {
    $mavenArgs += 'clean'
}
$mavenArgs += 'package'
if (-not $RunTests) {
    $mavenArgs += '-DskipTests'
}

Write-Host ("[{0}] [INFO] Packaging backend in {1}" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $projectRoot)
Push-Location $projectRoot
try {
    & $maven.Source @mavenArgs
    if ($LASTEXITCODE -ne 0) {
        throw "Maven build failed with exit code $LASTEXITCODE."
    }
}
finally {
    Pop-Location
}

$jarPath = Join-Path $projectRoot 'target\acmp-compute-1.0.0-SNAPSHOT.jar'
if (-not (Test-Path -LiteralPath $jarPath)) {
    throw "Build completed but JAR was not found: $jarPath"
}

Write-Host ("[{0}] [INFO] Backend JAR ready: {1}" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $jarPath)
