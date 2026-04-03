param(
    [switch]$ResetDatabase = $false
)

$ErrorActionPreference = "Stop"

$backendRoot = Split-Path -Parent $PSScriptRoot
$desktopModule = Join-Path $backendRoot "studio-desktop-runtime"
$arguments = @()
if ($ResetDatabase) {
    $arguments += "--studio.init.reset=true"
}

$runArguments = [string]::Join(" ", $arguments)

Push-Location $backendRoot
try {
    mvn -pl studio-desktop-runtime -am -DskipTests install
    Push-Location $desktopModule
    try {
        mvn -DskipTests "-Dspring-boot.run.main-class=com.jdragon.studio.desktopruntime.bootstrap.StudioDesktopDataInitializerApplication" "-Dspring-boot.run.arguments=$runArguments" spring-boot:run
    } finally {
        Pop-Location
    }
} finally {
    Pop-Location
}
