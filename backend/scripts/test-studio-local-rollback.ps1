[CmdletBinding()]
param(
    [string]$PreviousRevision = "HEAD",
    [ValidateRange(1024, 65535)]
    [int]$ServerPort = 19080,
    [ValidateRange(30, 600)]
    [int]$StartupTimeoutSeconds = 240,
    [string]$JavaHome = "C:\dev\Java\jdk-17.0.12"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$backendRoot = Split-Path -Parent $PSScriptRoot
$studioRoot = Split-Path -Parent $backendRoot
$aggregationHome = Join-Path (Split-Path -Parent $studioRoot) "package_all\aggregation"
$ideaWorkspace = Join-Path (Split-Path -Parent (Split-Path -Parent $studioRoot)) ".idea\workspace.xml"
$tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$drillId = [Guid]::NewGuid().ToString("N")
$containerName = "studio-p0mc02-rollback-$drillId"
$worktreePath = [IO.Path]::GetFullPath((Join-Path $tempRoot "studio-p0mc02-rollback-head-$drillId"))
$databaseName = "studio_rollback"
$databasePassword = "rollback-$drillId"
$serverProcess = $null
$worktreeCreated = $false
$containerCreated = $false
$savedEnvironment = @{}
$drillResult = $null

function Assert-CommandAvailable {
    param([Parameter(Mandatory = $true)][string]$Name)
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "$Name is required for the local rollback drill."
    }
}

function Test-TcpPort {
    param([Parameter(Mandatory = $true)][int]$Port)
    $client = $null
    try {
        $client = [Net.Sockets.TcpClient]::new()
        $attempt = $client.BeginConnect("127.0.0.1", $Port, $null, $null)
        if (-not $attempt.AsyncWaitHandle.WaitOne(500, $false)) {
            return $false
        }
        $client.EndConnect($attempt)
        return $true
    } catch {
        return $false
    } finally {
        if ($null -ne $client) {
            $client.Dispose()
        }
    }
}

function Set-ProcessEnvironmentValue {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [AllowNull()][string]$Value
    )
    if (-not $savedEnvironment.ContainsKey($Name)) {
        $savedEnvironment[$Name] = [Environment]::GetEnvironmentVariable(
            $Name, [EnvironmentVariableTarget]::Process)
    }
    [Environment]::SetEnvironmentVariable($Name, $Value, [EnvironmentVariableTarget]::Process)
}

function Restore-ProcessEnvironment {
    foreach ($entry in $savedEnvironment.GetEnumerator()) {
        [Environment]::SetEnvironmentVariable(
            [string]$entry.Key, $entry.Value, [EnvironmentVariableTarget]::Process)
    }
}

function Invoke-ContainerMysql {
    param(
        [Parameter(Mandatory = $true)][string]$Sql,
        [switch]$Scalar
    )
    $arguments = @("exec", "-e", "MYSQL_PWD=$databasePassword", $containerName, "mysql", "-uroot")
    if ($Scalar) {
        $arguments += @("-N", "-B")
    }
    $arguments += @($databaseName, "-e", $Sql)
    $output = & docker $arguments
    if ($LASTEXITCODE -ne 0) {
        throw "The isolated rollback database command failed."
    }
    if ($Scalar) {
        return ([string]($output | Select-Object -First 1)).Trim()
    }
}

function Import-SqlFile {
    param([Parameter(Mandatory = $true)][string]$Path)
    Get-Content -LiteralPath $Path -Raw -Encoding UTF8 |
        docker exec -i -e "MYSQL_PWD=$databasePassword" $containerName `
            mysql -uroot --default-character-set=utf8mb4 $databaseName
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to import an isolated rollback database fixture."
    }
}

function Get-RollbackInvariantFingerprint {
    $sql = @"
select lower(sha2(concat_ws('|',
  (select concat_ws(':', id, tenant_id, code, enabled, status)
     from studio_runtime_cluster where code = 'ROLLBACK-SENTINEL'),
  (select concat_ws(':', id, tenant_id, project_id, execution_type, status,
                    target_cluster_id, resource_revision, attempts, max_retries)
     from dispatch_task where execution_type = 'ROLLBACK_SENTINEL')
), 256));
"@
    return Invoke-ContainerMysql -Sql $sql -Scalar
}

function Get-RollbackSchemaFingerprint {
    $sql = @"
select lower(sha2(group_concat(concat(table_name, ':', column_name)
  order by table_name, ordinal_position separator '|'), 256))
from information_schema.columns
where table_schema = '$databaseName'
  and ((table_name = 'studio_runtime_cluster' and column_name in ('id','code','enabled','status'))
    or (table_name = 'dispatch_task' and column_name in
        ('id','target_cluster_id','resource_revision','claim_token','worker_boot_id','protected_payload_ciphertext')));
"@
    return Invoke-ContainerMysql -Sql $sql -Scalar
}

function Wait-ForPreviousServer {
    param([Parameter(Mandatory = $true)][System.Diagnostics.Process]$Process)
    $deadline = (Get-Date).AddSeconds($StartupTimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if ($Process.HasExited) {
            return $false
        }
        try {
            $response = Invoke-RestMethod `
                -Uri "http://127.0.0.1:$ServerPort/actuator/health" -TimeoutSec 10
            if ([string]$response.status -eq "UP") {
                return $true
            }
        } catch {
            Start-Sleep -Seconds 1
            continue
        }
        Start-Sleep -Seconds 1
    }
    return $false
}

try {
    Assert-CommandAvailable -Name "docker"
    Assert-CommandAvailable -Name "git"
    Assert-CommandAvailable -Name "mvn"
    if (-not (Test-Path -LiteralPath (Join-Path $JavaHome "bin\java.exe") -PathType Leaf)) {
        throw "A JDK 17+ Java runtime is required for the local rollback drill."
    }
    if (-not (Test-Path -LiteralPath (Join-Path $JavaHome "bin\jar.exe") -PathType Leaf)) {
        throw "A full JDK is required for the local rollback drill."
    }
    if (-not (Test-Path -LiteralPath (Join-Path $aggregationHome "plugin") -PathType Container) -or
            -not (Test-Path -LiteralPath (Join-Path $aggregationHome "conf\core.json") -PathType Leaf)) {
        throw "The previous Server plugin mount is incomplete."
    }
    if (-not $worktreePath.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase)) {
        throw "The rollback drill worktree must stay inside the operating-system temporary directory."
    }
    if (Test-TcpPort -Port $ServerPort) {
        throw "The rollback drill Server port is already in use."
    }

    & docker run -d --name $containerName -p "127.0.0.1::3306" `
        -e "MYSQL_ROOT_PASSWORD=$databasePassword" -e "MYSQL_DATABASE=$databaseName" `
        mysql:8.0.21 --character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to start the isolated rollback database."
    }
    $containerCreated = $true

    $databaseReady = $false
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        $previousErrorActionPreference = $ErrorActionPreference
        try {
            $ErrorActionPreference = "SilentlyContinue"
            & docker exec -e "MYSQL_PWD=$databasePassword" $containerName `
                mysql -uroot -N -B -e "select 1" *> $null
            $databaseProbeExitCode = $LASTEXITCODE
        } finally {
            $ErrorActionPreference = $previousErrorActionPreference
        }
        if ($databaseProbeExitCode -eq 0) {
            $databaseReady = $true
            break
        }
        Start-Sleep -Seconds 2
    }
    if (-not $databaseReady) {
        throw "The isolated rollback database did not become ready."
    }
    $portLine = [string](& docker port $containerName "3306/tcp" | Select-Object -First 1)
    if ($portLine -notmatch ':(?<port>\d+)$') {
        throw "Cannot determine the isolated rollback database port."
    }
    $databasePort = [int]$Matches.port

    Import-SqlFile -Path (Join-Path $studioRoot "backend\studio-server\src\main\resources\schema-mysql.sql")
    Import-SqlFile -Path (Join-Path $studioRoot "backend\studio-server\src\main\resources\data-mysql-base.sql")
    Invoke-ContainerMysql -Sql @"
insert into studio_runtime_cluster(id, tenant_id, code, name, enabled, status)
values(9100001, 'default', 'ROLLBACK-SENTINEL', 'Rollback Sentinel', 0, 'OFFLINE');
insert into dispatch_task(id, tenant_id, project_id, execution_type, status,
                          target_cluster_id, resource_revision, attempts, max_retries, payload_json)
values(9200001, 'default', 2047489207831650317, 'ROLLBACK_SENTINEL', 'STOPPED',
       9100001, 'rollback-r1', 0, 3, json_object('sentinel', true));
"@
    $invariantBefore = Get-RollbackInvariantFingerprint
    $schemaBefore = Get-RollbackSchemaFingerprint
    if ([string]::IsNullOrWhiteSpace($invariantBefore) -or [string]::IsNullOrWhiteSpace($schemaBefore)) {
        throw "The rollback drill database fingerprints were not created."
    }

    & git -C $studioRoot worktree add --detach $worktreePath $PreviousRevision
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to create the previous-version worktree."
    }
    $worktreeCreated = $true

    $oldBackend = Join-Path $worktreePath "backend"
    $oldPath = [Environment]::GetEnvironmentVariable("PATH", "Process")
    Set-ProcessEnvironmentValue -Name "JAVA_HOME" -Value $JavaHome
    Set-ProcessEnvironmentValue -Name "PATH" -Value ((Join-Path $JavaHome "bin") + ";" + $oldPath)
    $mavenCommand = (Get-Command "mvn").Source
    $mavenStdout = Join-Path $worktreePath "rollback-maven.stdout.log"
    $mavenStderr = Join-Path $worktreePath "rollback-maven.stderr.log"
    Push-Location $oldBackend
    try {
        & $mavenCommand -o -pl studio-server -am -DskipTests package `
            1> $mavenStdout 2> $mavenStderr
        $mavenExitCode = $LASTEXITCODE
    } finally {
        Pop-Location
    }
    if ($mavenExitCode -ne 0) {
        throw "The previous-version Server build failed."
    }

    if (Test-Path -LiteralPath $ideaWorkspace -PathType Leaf) {
        [xml]$workspace = Get-Content -LiteralPath $ideaWorkspace -Raw -Encoding UTF8
        $configuration = @($workspace.SelectNodes("//component[@name='RunManager']/configuration")) |
            Where-Object { [string]$_.name -eq "StudioServerApplication" } |
            Select-Object -First 1
        foreach ($entry in @($configuration.envs.env)) {
            $name = [string]$entry.name
            if ($name -match '^[A-Za-z_][A-Za-z0-9_]*$') {
                Set-ProcessEnvironmentValue -Name $name -Value ([string]$entry.value)
            }
        }
    }
    Set-ProcessEnvironmentValue -Name "SERVER_PORT" -Value ([string]$ServerPort)
    Set-ProcessEnvironmentValue -Name "SPRING_DATASOURCE_URL" -Value `
        "jdbc:mysql://127.0.0.1:$databasePort/${databaseName}?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true"
    Set-ProcessEnvironmentValue -Name "SPRING_DATASOURCE_USERNAME" -Value "root"
    Set-ProcessEnvironmentValue -Name "SPRING_DATASOURCE_PASSWORD" -Value $databasePassword
    Set-ProcessEnvironmentValue -Name "STUDIO_SCHEMA_AUTO_UPGRADE_ON_STARTUP" -Value "false"
    Set-ProcessEnvironmentValue -Name "STUDIO_AGGREGATION_HOME" -Value $aggregationHome
    Set-ProcessEnvironmentValue -Name "STUDIO_INSTANCE_ID" -Value "rollback-drill-previous-server"

    $previousJar = Get-ChildItem -LiteralPath (Join-Path $oldBackend "studio-server\target") `
        -Filter "*-exec.jar" | Select-Object -First 1
    if ($null -eq $previousJar) {
        throw "The previous-version executable Server jar was not produced."
    }
    $jarEntries = & (Join-Path $JavaHome "bin\jar.exe") tf $previousJar.FullName
    $executionArtifacts = @($jarEntries | Where-Object {
        $_ -match '^BOOT-INF/lib/(plugins-loader-center|data-source-handler-abstract)-.*\.jar$'
    })
    if ($executionArtifacts.Count -lt 2) {
        throw "The previous-version Server package does not contain the expected rollback execution runtime."
    }

    $stdout = Join-Path $worktreePath "rollback-server.stdout.log"
    $stderr = Join-Path $worktreePath "rollback-server.stderr.log"
    $serverProcess = Start-Process -FilePath (Join-Path $JavaHome "bin\java.exe") `
        -ArgumentList @("-jar", $previousJar.FullName) `
        -WorkingDirectory $worktreePath `
        -RedirectStandardOutput $stdout `
        -RedirectStandardError $stderr `
        -WindowStyle Hidden `
        -PassThru
    if (-not (Wait-ForPreviousServer -Process $serverProcess)) {
        throw "The previous-version Server did not become healthy on the current additive schema."
    }

    $invariantAfter = Get-RollbackInvariantFingerprint
    $schemaAfter = Get-RollbackSchemaFingerprint
    if ($invariantBefore -ne $invariantAfter) {
        throw "The previous-version Server changed runtime cluster or Dispatch placement invariants."
    }
    if ($schemaBefore -ne $schemaAfter) {
        throw "The previous-version Server changed the current runtime placement schema."
    }

    $drillResult = [ordered]@{
        schemaVersion = "studio.local-rollback-drill.v1"
        status = "PASS"
        previousRevision = [string](& git -C $worktreePath rev-parse --short=12 HEAD)
        previousServerHealth = "UP"
        currentSchemaCompatible = $true
        pluginMountComplete = $true
        previousExecutionArtifacts = $executionArtifacts.Count
        runtimePlacementInvariantsPreserved = $true
        temporaryResourcesCleaned = $false
    }
} finally {
    if ($null -ne $serverProcess -and -not $serverProcess.HasExited) {
        Stop-Process -Id $serverProcess.Id -Force -ErrorAction SilentlyContinue
        try {
            [void]$serverProcess.WaitForExit(10000)
        } catch {
            # Cleanup continues with the isolated container and worktree.
        }
    }
    Restore-ProcessEnvironment
    if ($containerCreated) {
        & docker container rm -f $containerName 2>$null | Out-Null
    }
    if ($worktreeCreated -and (Test-Path -LiteralPath $worktreePath)) {
        $previousErrorActionPreference = $ErrorActionPreference
        try {
            $ErrorActionPreference = "SilentlyContinue"
            & git -C $studioRoot worktree remove --force $worktreePath 2>$null | Out-Null
        } finally {
            $ErrorActionPreference = $previousErrorActionPreference
        }
        if (Test-Path -LiteralPath $worktreePath) {
            if (-not $worktreePath.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase) -or
                    -not ([IO.Path]::GetFileName($worktreePath)).StartsWith(
                        "studio-p0mc02-rollback-head-", [StringComparison]::OrdinalIgnoreCase)) {
                throw "The rollback worktree cleanup target is outside the verified temporary scope."
            }
            [IO.Directory]::Delete("\\?\$worktreePath", $true)
        }
    }
    & git -C $studioRoot worktree prune
    if ($null -ne $drillResult) {
        $containerRemains = @(& docker ps -a --filter "name=^/$containerName$" --format "{{.Names}}")
        $worktreeRemains = Test-Path -LiteralPath $worktreePath
        if ($containerRemains.Count -gt 0 -or $worktreeRemains) {
            throw "The local rollback drill did not clean all isolated resources."
        }
        $drillResult.temporaryResourcesCleaned = $true
    }
}

if ($null -ne $drillResult) {
    $drillResult | ConvertTo-Json
}
