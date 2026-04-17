param(
    [string]$InputDir = ""
)

$ErrorActionPreference = "Stop"

$backendRoot = Split-Path -Parent $PSScriptRoot
$repoRoot = Split-Path -Parent $backendRoot
$serverModule = Join-Path $backendRoot "studio-server"
if ([string]::IsNullOrWhiteSpace($InputDir)) {
    $InputDir = Join-Path $repoRoot "docs\数据库\结构快照\mysql\current"
}

Push-Location $backendRoot
try {
    mvn -pl studio-server -am -DskipTests install
    Push-Location $serverModule
    try {
        mvn -q -DskipTests "org.codehaus.mojo:exec-maven-plugin:3.1.0:java" "-Dexec.mainClass=com.jdragon.studio.server.bootstrap.StudioSchemaRestoreApplication" "-Dexec.cleanupDaemonThreads=false" "-Dexec.args=--studio.schema.snapshot.input-dir=$InputDir"
    } finally {
        Pop-Location
    }
} finally {
    Pop-Location
}
