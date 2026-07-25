param(
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"

$backendRoot = Split-Path -Parent $PSScriptRoot
$serverModule = Join-Path $backendRoot "studio-server"
$execArgs = if ($DryRun) { "--dry-run" } else { "" }

Push-Location $backendRoot
try {
    mvn -pl studio-server -am -DskipTests install
    Push-Location $serverModule
    try {
        mvn -q -DskipTests "org.codehaus.mojo:exec-maven-plugin:3.1.0:java" `
            "-Dexec.mainClass=com.jdragon.studio.server.bootstrap.StudioRuntimeClusterBackfillApplication" `
            "-Dexec.args=$execArgs" `
            "-Dexec.cleanupDaemonThreads=false"
    } finally {
        Pop-Location
    }
} finally {
    Pop-Location
}
