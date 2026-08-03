param(
    [string]$JarPath = "",
    [long]$MaxBytes = 67108864
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

if ([string]::IsNullOrWhiteSpace($JarPath)) {
    $JarPath = Join-Path $PSScriptRoot "..\..\studio-flink\target\studio-flink-0.1.0-SNAPSHOT-connector-remote-upload.jar"
}
$JarPath = [IO.Path]::GetFullPath($JarPath)

function Assert-True {
    param(
        [Parameter(Mandatory = $true)][bool]$Condition,
        [Parameter(Mandatory = $true)][string]$Message
    )
    if (-not $Condition) {
        throw $Message
    }
}

Add-Type -AssemblyName System.IO.Compression.FileSystem

Assert-True (Test-Path -LiteralPath $JarPath -PathType Leaf) "Remote connector JAR does not exist: $JarPath"
$jar = Get-Item -LiteralPath $JarPath
Assert-True ($jar.Length -le $MaxBytes) "Remote connector JAR is too large: $($jar.Length) bytes (limit: $MaxBytes)"

$archive = [IO.Compression.ZipFile]::OpenRead($JarPath)
try {
    $entryNames = @($archive.Entries | ForEach-Object { $_.FullName })
    $requiredEntries = @(
        "META-INF/services/org.apache.flink.table.factories.Factory",
        "com/jdragon/studio/flink/connector/ConnectorPluginRuntimeBootstrap.class",
        "com/jdragon/aggregation/pluginloader/runtime/ResolvedPlugin.class",
        "com/jdragon/aggregation/datasource/AbstractDataSourcePlugin.class"
    )
    foreach ($requiredEntry in $requiredEntries) {
        Assert-True ($entryNames -contains $requiredEntry) "Remote connector JAR is missing: $requiredEntry"
    }

    $bundledRuntimeEntries = @($entryNames | Where-Object { $_.StartsWith("dataaggregation-plugin-runtime/") })
    Assert-True ($bundledRuntimeEntries.Count -eq 0) "Remote connector JAR contains bundled plugin runtime entries"

    $concretePluginEntries = @($entryNames | Where-Object {
        $_ -match "com/jdragon/aggregation/datasource/(mysql|odps|oracle|postgres|dm|ftp|sftp|kafka|rabbitmq|rocketmq|influxdb|minio|tbds)"
    })
    Assert-True ($concretePluginEntries.Count -eq 0) "Remote connector JAR contains concrete source plugin classes"

    $bootstrapEntry = $archive.GetEntry("com/jdragon/studio/flink/connector/ConnectorPluginRuntimeBootstrap.class")
    $stream = $bootstrapEntry.Open()
    try {
        $buffer = [IO.MemoryStream]::new()
        try {
            $stream.CopyTo($buffer)
            $bootstrapBytes = [Text.Encoding]::ASCII.GetString($buffer.ToArray())
        } finally {
            $buffer.Dispose()
        }
    } finally {
        $stream.Dispose()
    }
    Assert-True ($bootstrapBytes.Contains("/api/flink/runtime/plugin/artifact")) `
        "Remote connector JAR does not contain the Worker artifact download path"
} finally {
    $archive.Dispose()
}

Write-Output "Remote Flink connector artifact test passed: $($jar.Name) ($($jar.Length) bytes)"
