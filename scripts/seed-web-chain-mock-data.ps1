param(
    [string]$DbHost = "192.168.188.129",
    [int]$Port = 3306,
    [string]$User = "root",
    [string]$Password = "951753",
    [string]$SourceDb = "mock_data",
    [string]$TargetDb = "mock_data_target"
)

$ErrorActionPreference = "Stop"

$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$pythonExe = "C:\Users\jdrag\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe"
$vendorDir = Join-Path $scriptRoot "..\tmp\pylibs"
$seedScript = Join-Path $scriptRoot "seed-web-chain-mock-data.py"

if (-not (Test-Path -LiteralPath $pythonExe)) {
    throw "Bundled Python runtime not found: $pythonExe"
}

if (-not (Test-Path -LiteralPath $vendorDir)) {
    New-Item -ItemType Directory -Path $vendorDir -Force | Out-Null
}

& $pythonExe -m pip install --disable-pip-version-check --target $vendorDir pymysql | Out-Host
if ($LASTEXITCODE -ne 0) {
    throw "Failed to install pymysql into $vendorDir"
}

$env:PYTHONPATH = $vendorDir

& $pythonExe $seedScript --host $DbHost --port $Port --user $User --password $Password --source-db $SourceDb --target-db $TargetDb
if ($LASTEXITCODE -ne 0) {
    throw "Mock data seed script failed."
}
