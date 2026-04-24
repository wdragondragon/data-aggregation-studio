<#
MySQL first initialization is now SQL-first:
1. Execute schema-mysql.sql
2. Execute data-mysql-base.sql
3. Execute data-mysql-builtin.sql

Use this script with -ResetDatabase only after the schema already exists.
SQLite/test initialization remains programmatic.
#>

param(
    [switch]$ResetDatabase = $false
)

$ErrorActionPreference = "Stop"

$backendRoot = Split-Path -Parent $PSScriptRoot
$serverModule = Join-Path $backendRoot "studio-server"
$arguments = @()
if ($ResetDatabase) {
    $arguments += "--studio.init.reset=true"
} else {
    Write-Host "[studio-init] MySQL first initialization is SQL-first. If the current datasource is MySQL, execute schema-mysql.sql, data-mysql-base.sql and data-mysql-builtin.sql first."
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
