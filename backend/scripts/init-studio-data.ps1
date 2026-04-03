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

$runArguments = [string]::Join(" ", $arguments)

Push-Location $backendRoot
try {
    mvn -pl studio-server -am -DskipTests install
    Push-Location $serverModule
    try {
        mvn -DskipTests "-Dspring-boot.run.main-class=com.jdragon.studio.server.bootstrap.StudioDataInitializerApplication" "-Dspring-boot.run.arguments=$runArguments" spring-boot:run
    } finally {
        Pop-Location
    }
} finally {
    Pop-Location
}
