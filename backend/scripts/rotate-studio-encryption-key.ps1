param(
    [switch]$Apply,
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"

function Require-EnvironmentValue {
    param([string]$Name)

    $value = [Environment]::GetEnvironmentVariable($Name, "Process")
    if ([string]::IsNullOrWhiteSpace($value)) {
        throw "$Name must be configured in the current process. Secrets must not be passed as command-line arguments."
    }
}

foreach ($name in @(
    "SPRING_DATASOURCE_URL",
    "STUDIO_ENCRYPTION_OLD_SECRET",
    "STUDIO_ENCRYPTION_NEW_SECRET"
)) {
    Require-EnvironmentValue -Name $name
}

if ($Apply -and $env:STUDIO_ENCRYPTION_ROTATION_CONFIRM -ne "ROTATE") {
    throw "Apply mode requires STUDIO_ENCRYPTION_ROTATION_CONFIRM=ROTATE. Run Dry Run first and take a database backup."
}

$mavenVersion = @(& mvn -version 2>&1)
if ($LASTEXITCODE -ne 0) {
    throw "Maven is unavailable. Install the project Maven toolchain before running encryption rotation."
}
$javaVersionMatch = [regex]::Match(($mavenVersion -join "`n"), "Java version:\s*(?:1\.)?(\d+)")
if (-not $javaVersionMatch.Success -or [int]$javaVersionMatch.Groups[1].Value -lt 17) {
    throw "Encryption rotation requires Maven to run with JDK 17 or newer. Check JAVA_HOME and PATH."
}

$backendRoot = Split-Path -Parent $PSScriptRoot
$serverModule = Join-Path $backendRoot "studio-server"

Push-Location -LiteralPath $backendRoot
try {
    if (-not $SkipBuild) {
        mvn -pl studio-server -am -DskipTests install
        if ($LASTEXITCODE -ne 0) {
            exit $LASTEXITCODE
        }
    }
    Push-Location -LiteralPath $serverModule
    try {
        $arguments = @(
            "-q",
            "-DskipTests",
            "org.codehaus.mojo:exec-maven-plugin:3.1.0:java",
            "-Dexec.mainClass=com.jdragon.studio.server.bootstrap.StudioEncryptionRotationApplication",
            "-Dexec.cleanupDaemonThreads=false"
        )
        if ($Apply) {
            $arguments += "-Dexec.args=--apply"
        }
        & mvn @arguments
        exit $LASTEXITCODE
    } finally {
        Pop-Location
    }
} finally {
    Pop-Location
}
