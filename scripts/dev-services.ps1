param(
    [ValidateSet("start", "stop", "restart", "status", "logs")]
    [string]$Action = "status",

    [ValidateSet("all", "frontend", "backend", "worker")]
    [string[]]$Services = @("all"),

    [int]$Tail = 80
)

$ErrorActionPreference = "Stop"

$script:StudioRoot = Split-Path -Parent $PSScriptRoot
$script:RuntimeRoot = Join-Path $StudioRoot "runtime\dev-services"
$script:ResolvedStudioJavaRuntime = $null
$script:IdeaWorkspacePath = Join-Path `
    (Split-Path -Parent $StudioRoot) ".idea\workspace.xml"

function Ensure-Directory {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) {
        New-Item -ItemType Directory -Path $Path -Force | Out-Null
    }
}

function ConvertTo-PowerShellSingleQuotedLiteral {
    param([AllowEmptyString()][string]$Value)

    return "'" + $Value.Replace("'", "''") + "'"
}

function ConvertTo-PowerShellCommandArgument {
    param([string]$Command)

    $encodedCommand = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($Command))
    return "&([ScriptBlock]::Create([Text.Encoding]::Unicode.GetString([Convert]::FromBase64String('$encodedCommand'))))"
}

function Get-IdeaRunConfigurationEnvironment {
    param([Parameter(Mandatory = $true)][string]$ConfigurationName)

    $environment = @{}
    if (-not (Test-Path -LiteralPath $script:IdeaWorkspacePath -PathType Leaf)) {
        return $environment
    }

    try {
        [xml]$workspace = Get-Content -LiteralPath $script:IdeaWorkspacePath -Raw -Encoding UTF8 -ErrorAction Stop
    } catch {
        throw "Unable to read IDEA workspace configuration without exposing its environment values."
    }

    $configuration = @($workspace.SelectNodes("//component[@name='RunManager']/configuration")) |
        Where-Object { [string]$_.name -eq $ConfigurationName } |
        Select-Object -First 1
    if ($null -eq $configuration) {
        return $environment
    }

    foreach ($entry in @($configuration.envs.env)) {
        $name = [string]$entry.name
        if ([string]::IsNullOrWhiteSpace($name)) {
            continue
        }
        if ($name -notmatch '^[A-Za-z_][A-Za-z0-9_]*$') {
            throw "IDEA run configuration $ConfigurationName contains an invalid environment variable name."
        }
        $environment[$name] = [string]$entry.value
    }
    return $environment
}

function Resolve-ConfiguredValue {
    param(
        [AllowNull()][string]$ProcessValue,
        [hashtable[]]$IdeaEnvironments,
        [Parameter(Mandatory = $true)][string]$Name,
        [AllowNull()][string]$DefaultValue
    )

    if (-not [string]::IsNullOrWhiteSpace($ProcessValue)) {
        return $ProcessValue
    }
    foreach ($environment in @($IdeaEnvironments)) {
        if ($null -ne $environment -and $environment.ContainsKey($Name) -and
                -not [string]::IsNullOrWhiteSpace([string]$environment[$Name])) {
            return [string]$environment[$Name]
        }
    }
    return $DefaultValue
}

function Copy-EnvironmentMap {
    param([hashtable]$Source)

    $copy = @{}
    if ($null -ne $Source) {
        foreach ($entry in $Source.GetEnumerator()) {
            $copy[$entry.Key] = $entry.Value
        }
    }
    return $copy
}

function Start-ProcessWithEnvironment {
    param(
        [Parameter(Mandatory = $true)][hashtable]$Environment,
        [Parameter(Mandatory = $true)][string]$CommandArgument,
        [Parameter(Mandatory = $true)][string]$WorkingDirectory,
        [Parameter(Mandatory = $true)][string]$StdOutLog,
        [Parameter(Mandatory = $true)][string]$StdErrLog
    )

    $savedEnvironment = @{}
    try {
        # Codex/Windows shells can inherit both `Path` and `PATH` in the native
        # environment block. Start-Process rejects that block as a duplicate-key
        # dictionary, so canonicalize it before spawning a managed service.
        $originalPath = [Environment]::GetEnvironmentVariable(
            "PATH", [EnvironmentVariableTarget]::Process)
        [Environment]::SetEnvironmentVariable(
            "PATH", $null, [EnvironmentVariableTarget]::Process)
        foreach ($entry in $Environment.GetEnumerator()) {
            $name = [string]$entry.Key
            $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable(
                $name, [EnvironmentVariableTarget]::Process)
            [Environment]::SetEnvironmentVariable(
                $name, $entry.Value, [EnvironmentVariableTarget]::Process)
        }
        # Callers that did not supply an explicit path entry (e.g. the npm
        # frontend) still need the original PATH restored so their executables
        # resolve. Java services define their own Path below.
        if (-not $Environment.ContainsKey("PATH") -and -not $Environment.ContainsKey("Path")) {
            [Environment]::SetEnvironmentVariable(
                "PATH", $originalPath, [EnvironmentVariableTarget]::Process)
        }
        $startedProcess = Start-Process `
            -FilePath "powershell.exe" `
            -ArgumentList @(
                "-NoLogo",
                "-NoProfile",
                "-ExecutionPolicy", "Bypass",
                "-Command", $CommandArgument
            ) `
            -WorkingDirectory $WorkingDirectory `
            -RedirectStandardOutput $StdOutLog `
            -RedirectStandardError $StdErrLog `
            -WindowStyle Hidden `
            -PassThru
        return $startedProcess
    } finally {
        foreach ($entry in $savedEnvironment.GetEnumerator()) {
            [Environment]::SetEnvironmentVariable(
                [string]$entry.Key, $entry.Value, [EnvironmentVariableTarget]::Process)
        }
    }
}

function Test-StudioJavaRuntime {
    param(
        [string]$Source,
        [string]$JavaHome
    )

    $result = [ordered]@{
        Source       = $Source
        Home         = $JavaHome
        MajorVersion = $null
        Version      = $null
        Valid        = $false
        Reason       = $null
    }

    try {
        $expandedHome = [Environment]::ExpandEnvironmentVariables($JavaHome.Trim().Trim('"'))
        $resolvedHome = (Resolve-Path -LiteralPath $expandedHome -ErrorAction Stop).Path
    } catch {
        $result.Reason = "directory does not exist"
        return [PSCustomObject]$result
    }

    $javaExe = Join-Path $resolvedHome "bin\java.exe"
    if (-not (Test-Path -LiteralPath $javaExe -PathType Leaf)) {
        $result.Home = $resolvedHome
        $result.Reason = "bin\java.exe does not exist"
        return [PSCustomObject]$result
    }
    if (-not (Test-Path -LiteralPath (Join-Path $resolvedHome "bin\javac.exe") -PathType Leaf)) {
        $result.Home = $resolvedHome
        $result.Reason = "bin\javac.exe does not exist"
        return [PSCustomObject]$result
    }

    $javaProcess = $null
    try {
        $startInfo = New-Object System.Diagnostics.ProcessStartInfo
        $startInfo.FileName = $javaExe
        $startInfo.Arguments = "-version"
        $startInfo.UseShellExecute = $false
        $startInfo.CreateNoWindow = $true
        $startInfo.RedirectStandardOutput = $true
        $startInfo.RedirectStandardError = $true
        $javaProcess = [System.Diagnostics.Process]::Start($startInfo)
        $versionOutput = $javaProcess.StandardOutput.ReadToEnd() + $javaProcess.StandardError.ReadToEnd()
        $javaProcess.WaitForExit()
        if ($javaProcess.ExitCode -ne 0) {
            throw "java -version exited with code $($javaProcess.ExitCode)"
        }
    } catch {
        $result.Home = $resolvedHome
        $result.Reason = $_.Exception.Message
        return [PSCustomObject]$result
    } finally {
        if ($javaProcess) {
            $javaProcess.Dispose()
        }
    }

    if ($versionOutput -notmatch '(?im)(?:java|openjdk) version "(?<version>[^"]+)"') {
        $result.Home = $resolvedHome
        $result.Reason = "cannot determine Java version"
        return [PSCustomObject]$result
    }

    $version = $Matches.version
    $majorVersion = if ($version -match '^1\.(?<major>\d+)') {
        [int]$Matches.major
    } elseif ($version -match '^(?<major>\d+)') {
        [int]$Matches.major
    } else {
        $null
    }

    $result.Home = $resolvedHome
    $result.MajorVersion = $majorVersion
    $result.Version = $version
    if (-not $majorVersion) {
        $result.Reason = "cannot determine Java major version from $version"
    } elseif ($majorVersion -lt 17) {
        $result.Reason = "Java $majorVersion is older than required JDK 17"
    } else {
        $result.Valid = $true
    }
    return [PSCustomObject]$result
}

function Resolve-StudioJavaRuntime {
    param([string]$FallbackJavaHome = "C:\dev\Java\jdk-17.0.12")

    $candidates = @(
        [PSCustomObject]@{ Source = "STUDIO_JAVA_HOME"; Home = $env:STUDIO_JAVA_HOME },
        [PSCustomObject]@{ Source = "BOOT3_JAVA_HOME"; Home = $env:BOOT3_JAVA_HOME },
        [PSCustomObject]@{ Source = "JAVA_HOME"; Home = $env:JAVA_HOME },
        [PSCustomObject]@{ Source = "fallback"; Home = $FallbackJavaHome }
    )
    $rejected = @()

    foreach ($candidate in $candidates) {
        if ([string]::IsNullOrWhiteSpace($candidate.Home)) {
            continue
        }
        $runtime = Test-StudioJavaRuntime -Source $candidate.Source -JavaHome $candidate.Home
        if ($runtime.Valid) {
            return $runtime
        }
        $rejected += "$($candidate.Source)=$($candidate.Home) ($($runtime.Reason))"
    }

    $checked = if ($rejected.Count -gt 0) { $rejected -join "; " } else { "no configured candidates" }
    throw "JDK 17+ is required to start Studio backend services. Set STUDIO_JAVA_HOME or BOOT3_JAVA_HOME. Checked: $checked"
}

function New-ServiceDefinition {
    param(
        [string]$Name,
        [string]$Workdir,
        [string]$Command,
        [int]$Port,
        [string]$PrimaryUrl,
        [string[]]$AlternateUrls = @(),
        [int]$StartupTimeoutSeconds = 120,
        [hashtable]$Environment = @{},
        [int]$IdeaEnvironmentCount = 0
    )

    $serviceRuntimeDir = Join-Path $RuntimeRoot $Name
    Ensure-Directory -Path $serviceRuntimeDir

    return [PSCustomObject]@{
        Name          = $Name
        Workdir       = $Workdir
        Command       = $Command
        Port          = $Port
        PrimaryUrl    = $PrimaryUrl
        AlternateUrls = $AlternateUrls
        StartupTimeoutSeconds = $StartupTimeoutSeconds
        Environment   = $Environment
        IdeaEnvironmentCount = $IdeaEnvironmentCount
        PidFile       = Join-Path $serviceRuntimeDir "service.pid"
        StdOutLog     = Join-Path $serviceRuntimeDir "stdout.log"
        StdErrLog     = Join-Path $serviceRuntimeDir "stderr.log"
    }
}

Ensure-Directory -Path $RuntimeRoot

$frontendWorkdir = Join-Path $StudioRoot "frontend"
$backendWorkdir = Join-Path $StudioRoot "backend"
$backendUpgradeScriptPath = Join-Path $backendWorkdir "scripts\upgrade-studio-schema.ps1"
$serverIdeaEnvironment = Get-IdeaRunConfigurationEnvironment -ConfigurationName "StudioServerApplication"
$workerIdeaEnvironment = Get-IdeaRunConfigurationEnvironment -ConfigurationName "StudioWorkerApplication"
$frontendApiBaseUrl = if ($env:VITE_API_BASE_URL) { $env:VITE_API_BASE_URL } else { "/api/v1" }
$studioInternalToken = Resolve-ConfiguredValue -ProcessValue $env:STUDIO_INTERNAL_API_TOKEN `
    -IdeaEnvironments @($serverIdeaEnvironment, $workerIdeaEnvironment) `
    -Name "STUDIO_INTERNAL_API_TOKEN" -DefaultValue "studio-api-token"
$studioEncryptionSecret = Resolve-ConfiguredValue -ProcessValue $env:STUDIO_ENCRYPTION_SECRET `
    -IdeaEnvironments @($serverIdeaEnvironment, $workerIdeaEnvironment) `
    -Name "STUDIO_ENCRYPTION_SECRET" -DefaultValue "studio-encryption-key"
$studioAggregationHome = Resolve-ConfiguredValue -ProcessValue $env:STUDIO_AGGREGATION_HOME `
    -IdeaEnvironments @($workerIdeaEnvironment) -Name "STUDIO_AGGREGATION_HOME" `
    -DefaultValue (Join-Path (Split-Path -Parent $StudioRoot) "package_all\aggregation")
$studioClusterCode = Resolve-ConfiguredValue -ProcessValue $env:STUDIO_CLUSTER_CODE `
    -IdeaEnvironments @($workerIdeaEnvironment) -Name "STUDIO_CLUSTER_CODE" `
    -DefaultValue "DEFAULT-LOCAL"
$studioWorkerSchedulerPoolSize = if ($env:STUDIO_WORKER_SCHEDULER_POOL_SIZE) {
    $env:STUDIO_WORKER_SCHEDULER_POOL_SIZE
} else {
    "4"
}
$frontendApiBaseUrlLiteral = ConvertTo-PowerShellSingleQuotedLiteral -Value $frontendApiBaseUrl
$frontendWorkdirLiteral = ConvertTo-PowerShellSingleQuotedLiteral -Value $frontendWorkdir
$backendWorkdirLiteral = ConvertTo-PowerShellSingleQuotedLiteral -Value $backendWorkdir
$backendUpgradeScriptPathLiteral = ConvertTo-PowerShellSingleQuotedLiteral -Value $backendUpgradeScriptPath

$serverProcessEnvironment = Copy-EnvironmentMap -Source $serverIdeaEnvironment
foreach ($workerOnlyName in @(
        "STUDIO_CLUSTER_CODE",
        "STUDIO_AGGREGATION_HOME",
        "STUDIO_WORKER_GROUP_CODE",
        "STUDIO_WORKER_CODE",
        "STUDIO_WORKER_API_BASE_URL",
        "STUDIO_RUNTIME_VERSION",
        "STUDIO_PLUGIN_FINGERPRINT",
        "STUDIO_PYTHON_EXECUTABLE",
        "STUDIO_PYTHON_TEMP_DIR",
        "STUDIO_PYTHON_TIMEOUT_SECONDS",
        "STUDIO_SCRIPT_ARTIFACT_CONNECT_TIMEOUT_SECONDS",
        "STUDIO_SCRIPT_ARTIFACT_READ_TIMEOUT_SECONDS",
        "STUDIO_SCRIPT_ARTIFACT_MAX_BYTES",
        "STUDIO_SCRIPT_ARTIFACT_ALLOW_LOCAL_FILES",
        "STUDIO_SCRIPT_ARTIFACT_ALLOWED_LOCAL_ROOTS")) {
    $serverProcessEnvironment[$workerOnlyName] = $null
}
$serverProcessEnvironment["STUDIO_INTERNAL_API_TOKEN"] = $studioInternalToken
$serverProcessEnvironment["STUDIO_ENCRYPTION_SECRET"] = $studioEncryptionSecret
$serverProcessEnvironment["STUDIO_GATEWAY_TRUST_ENABLED"] = "false"
$serverProcessEnvironment["STUDIO_RUNTIME_ENDPOINT_ALLOWED_HOSTS"] = "127.0.0.1,localhost"

$workerProcessEnvironment = Copy-EnvironmentMap -Source $workerIdeaEnvironment
$workerProcessEnvironment["STUDIO_INTERNAL_API_TOKEN"] = $studioInternalToken
$workerProcessEnvironment["STUDIO_ENCRYPTION_SECRET"] = $studioEncryptionSecret
$workerProcessEnvironment["STUDIO_GATEWAY_TRUST_ENABLED"] = "false"
$workerProcessEnvironment["STUDIO_CLUSTER_CODE"] = $studioClusterCode
$workerProcessEnvironment["STUDIO_AGGREGATION_HOME"] = $studioAggregationHome
$workerProcessEnvironment["STUDIO_WORKER_SCHEDULER_POOL_SIZE"] = $studioWorkerSchedulerPoolSize

$serviceDefinitions = @{
    frontend = New-ServiceDefinition `
        -Name "frontend" `
        -Workdir $frontendWorkdir `
        -Command "`$env:VITE_API_BASE_URL=$frontendApiBaseUrlLiteral; Set-Location -LiteralPath $frontendWorkdirLiteral; npm run dev:web" `
        -Port 5173 `
        -PrimaryUrl "http://localhost:5173" `
        -AlternateUrls @("http://127.0.0.1:5173")
    backend = New-ServiceDefinition `
        -Name "backend" `
        -Workdir $backendWorkdir `
        -Command "Set-Location -LiteralPath $backendWorkdirLiteral; powershell -ExecutionPolicy Bypass -File $backendUpgradeScriptPathLiteral; if (`$LASTEXITCODE -ne 0) { exit `$LASTEXITCODE }; mvn -o -pl studio-server -am '-Dmaven.test.skip=true' install; if (`$LASTEXITCODE -ne 0) { exit `$LASTEXITCODE }; mvn -o -pl studio-server spring-boot:run" `
        -Port 18080 `
        -PrimaryUrl "http://127.0.0.1:18080" `
        -AlternateUrls @("http://localhost:18080") `
        -StartupTimeoutSeconds 900 `
        -Environment $serverProcessEnvironment `
        -IdeaEnvironmentCount $serverIdeaEnvironment.Count
    worker = New-ServiceDefinition `
        -Name "worker" `
        -Workdir $backendWorkdir `
        -Command "Set-Location -LiteralPath $backendWorkdirLiteral; mvn -o -pl studio-worker -am '-Dmaven.test.skip=true' install; if (`$LASTEXITCODE -ne 0) { exit `$LASTEXITCODE }; mvn -o -pl studio-worker spring-boot:run" `
        -Port 18081 `
        -PrimaryUrl "http://127.0.0.1:18081" `
        -AlternateUrls @("http://localhost:18081") `
        -StartupTimeoutSeconds 900 `
        -Environment $workerProcessEnvironment `
        -IdeaEnvironmentCount $workerIdeaEnvironment.Count
}

function Resolve-Services {
    param([string[]]$Requested)

    if ($Requested -contains "all") {
        return @($serviceDefinitions.frontend, $serviceDefinitions.backend, $serviceDefinitions.worker)
    }

    $resolved = @()
    foreach ($name in $Requested) {
        $definition = $serviceDefinitions[$name]
        if (-not $definition) {
            throw "Unknown service: $name"
        }
        $resolved += $definition
    }
    return $resolved
}

function Test-TcpPort {
    param([int]$Port)

    try {
        $client = New-Object System.Net.Sockets.TcpClient
        $iar = $client.BeginConnect("127.0.0.1", $Port, $null, $null)
        $connected = $iar.AsyncWaitHandle.WaitOne(1000, $false)
        if (-not $connected) {
            $client.Close()
            return $false
        }
        $client.EndConnect($iar)
        $client.Close()
        return $true
    } catch {
        return $false
    }
}

function Read-ServiceProcessRecord {
    param($Service)

    if (-not (Test-Path -LiteralPath $Service.PidFile)) {
        return $null
    }

    $raw = Get-Content -LiteralPath $Service.PidFile -Raw -ErrorAction SilentlyContinue
    if (-not $raw) {
        return $null
    }
    $raw = $raw.Trim()
    if (-not $raw) {
        return $null
    }

    if ($raw.StartsWith("{")) {
        try {
            $record = $raw | ConvertFrom-Json -ErrorAction Stop
            if (-not $record.Pid) {
                return $null
            }
            return [PSCustomObject]@{
                Pid          = [int]$record.Pid
                StartedAtUtc = [string]$record.StartedAtUtc
                ProcessName  = [string]$record.ProcessName
            }
        } catch {
            return $null
        }
    }

    # Legacy PID-only files cannot prove process identity after PID reuse.
    # Drop them instead of allowing a destructive stop against an unrelated process.
    return $null
}

function Get-ServiceProcess {
    param($Service)

    $record = Read-ServiceProcessRecord -Service $Service
    if (-not $record) {
        return $null
    }

    try {
        $process = Get-Process -Id $record.Pid -ErrorAction Stop
        if ($record.ProcessName -and $process.ProcessName -ne $record.ProcessName) {
            return $null
        }
        if ($record.StartedAtUtc) {
            $expectedStart = [DateTime]::Parse(
                $record.StartedAtUtc,
                [Globalization.CultureInfo]::InvariantCulture,
                [Globalization.DateTimeStyles]::RoundtripKind)
            $actualStart = $process.StartTime.ToUniversalTime()
            if ([Math]::Abs(($actualStart - $expectedStart.ToUniversalTime()).TotalSeconds) -gt 2) {
                return $null
            }
        }
        return $process
    } catch {
        return $null
    }
}

function Remove-ServicePid {
    param($Service)

    if (Test-Path -LiteralPath $Service.PidFile) {
        Remove-Item -LiteralPath $Service.PidFile -Force -ErrorAction SilentlyContinue
    }
}

function Wait-PortUp {
    param(
        [int]$Port,
        [System.Diagnostics.Process]$Process,
        [int]$TimeoutSeconds = 120
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if ($Process -and $Process.HasExited) {
            return $false
        }
        if (Test-TcpPort -Port $Port) {
            return $true
        }
        Start-Sleep -Seconds 1
    }
    return (Test-TcpPort -Port $Port)
}

function Start-ManagedService {
    param($Service)

    $process = Get-ServiceProcess -Service $Service
    if ($process -and -not $process.HasExited) {
        Write-Host "[$($Service.Name)] already running (PID $($process.Id)) at $($Service.PrimaryUrl)"
        return
    }

    Remove-ServicePid -Service $Service
    if (Test-TcpPort -Port $Service.Port) {
        Write-Host "[$($Service.Name)] already available at $($Service.PrimaryUrl) (port $($Service.Port) is owned outside this script)"
        return
    }

    $serviceCommand = $Service.Command
    $processEnvironment = Copy-EnvironmentMap -Source $Service.Environment
    if ($Service.IdeaEnvironmentCount -gt 0) {
        Write-Host "[$($Service.Name)] loaded $($Service.IdeaEnvironmentCount) environment variables from the IDEA run configuration"
    }
    if ($Service.Name -in @("backend", "worker")) {
        $javaRuntime = if ($script:ResolvedStudioJavaRuntime) {
            $script:ResolvedStudioJavaRuntime
        } else {
            Resolve-StudioJavaRuntime
        }
        $processEnvironment["JAVA_HOME"] = $javaRuntime.Home
        # Windows treats environment names case-insensitively, while Start-Process can see
        # both inherited "Path" and an added "PATH" as duplicate dictionary keys.
        $processEnvironment.Remove("PATH")
        $processEnvironment["Path"] = (Join-Path $javaRuntime.Home "bin") + ";" + $env:PATH
        Write-Host "[$($Service.Name)] using Java $($javaRuntime.Version) from $($javaRuntime.Source): $($javaRuntime.Home)"
    }

    foreach ($logFile in @($Service.StdOutLog, $Service.StdErrLog)) {
        if (Test-Path -LiteralPath $logFile) {
            Remove-Item -LiteralPath $logFile -Force -ErrorAction SilentlyContinue
        }
    }

    # Start-Process flattens ArgumentList; this wrapper has no spaces and restores the full script in the child.
    $commandArgument = ConvertTo-PowerShellCommandArgument -Command $serviceCommand
    $process = Start-ProcessWithEnvironment `
        -Environment $processEnvironment `
        -CommandArgument $commandArgument `
        -WorkingDirectory $Service.Workdir `
        -StdOutLog $Service.StdOutLog `
        -StdErrLog $Service.StdErrLog

    $processRecord = [ordered]@{
        Pid          = $process.Id
        StartedAtUtc = $process.StartTime.ToUniversalTime().ToString("O")
        ProcessName  = $process.ProcessName
    }
    Set-Content -LiteralPath $Service.PidFile -Value ($processRecord | ConvertTo-Json -Compress) -Encoding ASCII

    Write-Host "[$($Service.Name)] starting (PID $($process.Id)) ..."

    if (Wait-PortUp -Port $Service.Port -Process $process -TimeoutSeconds $Service.StartupTimeoutSeconds) {
        Write-Host "[$($Service.Name)] ready at $($Service.PrimaryUrl)"
        return
    }

    $stillRunning = Get-ServiceProcess -Service $Service
    if ($stillRunning -and -not $stillRunning.HasExited) {
        cmd /c "taskkill /PID $($stillRunning.Id) /T /F" | Out-Null
        Start-Sleep -Seconds 1
        Remove-ServicePid -Service $Service
        throw "[$($Service.Name)] did not expose port $($Service.Port) in time. Check logs: $($Service.StdOutLog)"
    }

    $exitCode = $process.ExitCode
    Remove-ServicePid -Service $Service
    throw "[$($Service.Name)] exited during startup with code $exitCode. Check logs: $($Service.StdOutLog) and $($Service.StdErrLog)"
}

function Stop-ManagedService {
    param($Service)

    $process = Get-ServiceProcess -Service $Service
    if (-not $process) {
        Remove-ServicePid -Service $Service
        Write-Host "[$($Service.Name)] already stopped"
        return
    }

    Write-Host "[$($Service.Name)] stopping PID $($process.Id) ..."
    cmd /c "taskkill /PID $($process.Id) /T /F" | Out-Null
    Start-Sleep -Seconds 1
    Remove-ServicePid -Service $Service
    Write-Host "[$($Service.Name)] stopped"
}

function Show-ServiceStatus {
    param($Service)

    $process = Get-ServiceProcess -Service $Service
    $isRunning = [bool]($process -and -not $process.HasExited)
    $portUp = Test-TcpPort -Port $Service.Port
    $state = if ($isRunning -or $portUp) { "RUNNING" } else { "STOPPED" }
    $pidText = if ($process) { $process.Id } else { "-" }

    Write-Host "[$($Service.Name)] $state"
    Write-Host "  PID: $pidText"
    Write-Host "  Port: $($Service.Port)"
    Write-Host "  URL: $($Service.PrimaryUrl)"
    foreach ($url in $Service.AlternateUrls) {
        Write-Host "  Alt URL: $url"
    }
    Write-Host "  Stdout: $($Service.StdOutLog)"
    Write-Host "  Stderr: $($Service.StdErrLog)"
}

function Show-ServiceLogs {
    param(
        $Service,
        [int]$TailCount
    )

    Write-Host "[$($Service.Name)] stdout tail ($TailCount)"
    if (Test-Path -LiteralPath $Service.StdOutLog) {
        Get-Content -LiteralPath $Service.StdOutLog -Tail $TailCount
    } else {
        Write-Host "  <no stdout log yet>"
    }

    Write-Host ""
    Write-Host "[$($Service.Name)] stderr tail ($TailCount)"
    if (Test-Path -LiteralPath $Service.StdErrLog) {
        Get-Content -LiteralPath $Service.StdErrLog -Tail $TailCount
    } else {
        Write-Host "  <no stderr log yet>"
    }
}

$resolvedServices = Resolve-Services -Requested $Services
$requiresJava = @($resolvedServices | Where-Object { $_.Name -in @("backend", "worker") }).Count -gt 0
if (($Action -in @("start", "restart")) -and $requiresJava) {
    $script:ResolvedStudioJavaRuntime = Resolve-StudioJavaRuntime
}

switch ($Action) {
    "start" {
        foreach ($service in $resolvedServices) {
            Start-ManagedService -Service $service
        }
    }
    "stop" {
        foreach ($service in $resolvedServices) {
            Stop-ManagedService -Service $service
        }
    }
    "restart" {
        foreach ($service in $resolvedServices) {
            Stop-ManagedService -Service $service
        }
        foreach ($service in $resolvedServices) {
            Start-ManagedService -Service $service
        }
    }
    "status" {
        foreach ($service in $resolvedServices) {
            Show-ServiceStatus -Service $service
        }
    }
    "logs" {
        foreach ($service in $resolvedServices) {
            Show-ServiceLogs -Service $service -TailCount $Tail
        }
    }
}
