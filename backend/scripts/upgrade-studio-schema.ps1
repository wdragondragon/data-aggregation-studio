param()

$ErrorActionPreference = "Stop"

$backendRoot = Split-Path -Parent $PSScriptRoot
$serverModule = Join-Path $backendRoot "studio-server"

Push-Location $backendRoot
try {
    mvn -pl studio-server -am "-Dmaven.test.skip=true" install
    if ($LASTEXITCODE -ne 0) {
        throw "Studio schema upgrade build failed with exit code $LASTEXITCODE"
    }
    Push-Location $serverModule
    try {
        mvn -q "-Dmaven.test.skip=true" "org.codehaus.mojo:exec-maven-plugin:3.1.0:java" "-Dexec.mainClass=com.jdragon.studio.server.bootstrap.StudioSchemaUpgradeApplication" "-Dexec.cleanupDaemonThreads=false"
        if ($LASTEXITCODE -ne 0) {
            throw "Studio schema upgrade execution failed with exit code $LASTEXITCODE"
        }
    } finally {
        Pop-Location
    }
} finally {
    Pop-Location
}
