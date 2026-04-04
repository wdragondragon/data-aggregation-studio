param()

$ErrorActionPreference = "Stop"

$backendRoot = Split-Path -Parent $PSScriptRoot
$serverModule = Join-Path $backendRoot "studio-server"

Push-Location $backendRoot
try {
    mvn -pl studio-server -am -DskipTests install
    Push-Location $serverModule
    try {
        mvn -q -DskipTests "org.codehaus.mojo:exec-maven-plugin:3.1.0:java" "-Dexec.mainClass=com.jdragon.studio.server.bootstrap.StudioSchemaUpgradeApplication" "-Dexec.cleanupDaemonThreads=false"
    } finally {
        Pop-Location
    }
} finally {
    Pop-Location
}
