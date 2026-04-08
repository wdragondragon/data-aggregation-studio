param(
    [switch]$ResetDatabase = $false
)

$ErrorActionPreference = "Stop"

$backendRoot = Split-Path -Parent $PSScriptRoot
$serverModule = Join-Path $backendRoot "studio-server"
$arguments = @()
if ($ResetDatabase) {
    $arguments += "--studio.init.reset=true"
}

$execArguments = @(
    "-q",
    "-DskipTests",
    "org.codehaus.mojo:exec-maven-plugin:3.1.0:java",
    "-Dexec.mainClass=com.jdragon.studio.server.bootstrap.StudioDataInitializerApplication",
    "-Dexec.cleanupDaemonThreads=false"
)
if ($arguments.Count -gt 0) {
    $runArguments = [string]::Join(" ", $arguments)
    $execArguments += "-Dexec.args=$runArguments"
}

Push-Location $backendRoot
try {
    mvn -pl studio-server -am -DskipTests install
    Push-Location $serverModule
    try {
        & mvn @execArguments
    } finally {
        Pop-Location
    }
} finally {
    Pop-Location
}
