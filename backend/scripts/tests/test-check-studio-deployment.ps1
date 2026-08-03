$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$preflightScript = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\check-studio-deployment.ps1"))
$temporaryBase = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$temporaryRoot = [IO.Path]::GetFullPath((Join-Path $temporaryBase ("studio-deployment-preflight-test-" + [Guid]::NewGuid().ToString("N"))))
$secretMarkers = @(
    "test-internal-token-7cba12",
    "test-encryption-secret-30df91",
    "test-object-access-82d0f4",
    "test-object-secret-647e5a"
)
$managedEnvironmentNames = @(
    "STUDIO_RUNTIME_CLUSTER_MODE",
    "STUDIO_RUNTIME_CLUSTER_BACKFILL_ENABLED",
    "STUDIO_RUNTIME_CLUSTER_BACKFILL_DRY_RUN",
    "STUDIO_INTERNAL_API_TOKEN",
    "STUDIO_ENCRYPTION_SECRET",
    "STUDIO_GATEWAY_TRUST_ENABLED",
    "STUDIO_GATEWAY_SHARED_SECRET",
    "STUDIO_CLUSTER_CODE",
    "STUDIO_AGGREGATION_HOME",
    "STUDIO_WORKER_GROUP_CODE",
    "STUDIO_WORKER_CODE",
    "STUDIO_WORKER_API_BASE_URL",
    "STUDIO_RUNTIME_VERSION",
    "STUDIO_PLUGIN_FINGERPRINT",
    "STUDIO_PLUGIN_RUNTIME_MODE",
    "STUDIO_PLUGIN_BUCKET",
    "STUDIO_PLUGIN_PREFIX",
    "STUDIO_PLUGIN_CHANNEL",
    "STUDIO_PLUGIN_REFRESH_INTERVAL_SECONDS",
    "STUDIO_PLUGIN_REFRESH_JITTER_SECONDS",
    "STUDIO_PLUGIN_COLD_LOAD_TIMEOUT_SECONDS",
    "STUDIO_PLUGIN_MAX_ARTIFACT_BYTES",
    "STUDIO_PLUGIN_MAX_EXTRACTED_BYTES",
    "STUDIO_PLUGIN_MAX_ENTRY_COUNT",
    "STUDIO_PLUGIN_CACHE_MAX_BYTES",
    "STUDIO_PLUGIN_RETAINED_RELEASES",
    "STUDIO_PYTHON_EXECUTABLE",
    "STUDIO_PYTHON_TEMP_DIR",
    "STUDIO_PYTHON_TIMEOUT_SECONDS",
    "STUDIO_SCRIPT_ARTIFACT_CONNECT_TIMEOUT_SECONDS",
    "STUDIO_SCRIPT_ARTIFACT_READ_TIMEOUT_SECONDS",
    "STUDIO_SCRIPT_ARTIFACT_MAX_BYTES",
    "STUDIO_SCRIPT_ARTIFACT_ALLOW_LOCAL_FILES",
    "STUDIO_SCRIPT_ARTIFACT_ALLOWED_LOCAL_ROOTS",
    "STUDIO_RUN_LOG_STORAGE_TYPE",
    "STUDIO_OBJECT_PROVIDER",
    "STUDIO_RUN_LOG_OBJECT_PROVIDER",
    "STUDIO_OBJECT_ENDPOINT",
    "STUDIO_RUN_LOG_OBJECT_ENDPOINT",
    "STUDIO_OBJECT_ACCESS_KEY",
    "STUDIO_RUN_LOG_OBJECT_ACCESS_KEY",
    "STUDIO_OBJECT_SECRET_KEY",
    "STUDIO_RUN_LOG_OBJECT_SECRET_KEY",
    "STUDIO_OBJECT_BUCKET",
    "STUDIO_RUN_LOG_OBJECT_BUCKET"
)

function Assert-True {
    param(
        [Parameter(Mandatory = $true)][bool]$Condition,
        [Parameter(Mandatory = $true)][string]$Message
    )
    if (-not $Condition) {
        throw $Message
    }
}

function ConvertTo-PowerShellSingleQuotedLiteral {
    param([Parameter(Mandatory = $true)][string]$Value)
    return "'" + $Value.Replace("'", "''") + "'"
}

function New-BaseEnvironment {
    return @{
        STUDIO_INTERNAL_API_TOKEN = $secretMarkers[0]
        STUDIO_ENCRYPTION_SECRET = $secretMarkers[1]
        STUDIO_GATEWAY_TRUST_ENABLED = "false"
    }
}

function Invoke-DeploymentPreflight {
    param(
        [Parameter(Mandatory = $true)][ValidateSet("Server", "Worker", "Flink")][string]$Role,
        [Parameter(Mandatory = $true)][hashtable]$Environment,
        [switch]$RequireSharedObjectStorage
    )

    $stdoutPath = Join-Path $temporaryRoot ([Guid]::NewGuid().ToString("N") + ".stdout.log")
    $stderrPath = Join-Path $temporaryRoot ([Guid]::NewGuid().ToString("N") + ".stderr.log")
    $scriptLiteral = ConvertTo-PowerShellSingleQuotedLiteral -Value $preflightScript
    $sharedSwitch = if ($RequireSharedObjectStorage) { " -RequireSharedObjectStorage" } else { "" }
    $command = "& $scriptLiteral -Role $Role$sharedSwitch; exit `$LASTEXITCODE"
    $encodedCommand = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($command))

    $startInfo = [Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = "powershell.exe"
    $startInfo.Arguments = "-NoLogo -NoProfile -ExecutionPolicy Bypass -EncodedCommand $encodedCommand"
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    foreach ($name in $managedEnvironmentNames) {
        [void]$startInfo.EnvironmentVariables.Remove($name)
    }
    foreach ($entry in $Environment.GetEnumerator()) {
        $startInfo.EnvironmentVariables[[string]$entry.Key] = [string]$entry.Value
    }

    $process = [Diagnostics.Process]::Start($startInfo)
    try {
        $stdoutTask = $process.StandardOutput.ReadToEndAsync()
        $stderrTask = $process.StandardError.ReadToEndAsync()
        $process.WaitForExit()
        $stdout = $stdoutTask.GetAwaiter().GetResult()
        $stderr = $stderrTask.GetAwaiter().GetResult()
        [IO.File]::WriteAllText($stdoutPath, $stdout, [Text.UTF8Encoding]::new($false))
        [IO.File]::WriteAllText($stderrPath, $stderr, [Text.UTF8Encoding]::new($false))
        $combinedOutput = $stdout + "`n" + $stderr
        foreach ($secretMarker in $secretMarkers) {
            Assert-True -Condition (-not $combinedOutput.Contains($secretMarker)) `
                -Message "Deployment preflight output leaked a secret value."
        }
        return [PSCustomObject]@{
            ExitCode = $process.ExitCode
            Output = $combinedOutput
        }
    } finally {
        $process.Dispose()
    }
}

try {
    [void](New-Item -ItemType Directory -Path $temporaryRoot -Force)

    $completeHome = Join-Path $temporaryRoot "complete-aggregation-home"
    foreach ($relativePath in @("conf", "plugin/source", "plugin/reader", "plugin/writer", "plugin/transformer")) {
        [void](New-Item -ItemType Directory -Path (Join-Path $completeHome $relativePath) -Force)
    }
    [IO.File]::WriteAllText((Join-Path $completeHome "conf/core.json"), "{}", [Text.UTF8Encoding]::new($false))

    $serverResult = Invoke-DeploymentPreflight -Role Server -Environment (New-BaseEnvironment)
    Assert-True -Condition ($serverResult.ExitCode -eq 0) -Message "Valid Server environment should pass preflight."

    $serverPythonEnvironment = New-BaseEnvironment
    $serverPythonEnvironment.STUDIO_PYTHON_EXECUTABLE = "C:\\worker-only\\python.exe"
    $serverPythonResult = Invoke-DeploymentPreflight -Role Server -Environment $serverPythonEnvironment
    Assert-True -Condition ($serverPythonResult.ExitCode -ne 0) -Message "Server Python settings must fail preflight."
    Assert-True -Condition ($serverPythonResult.Output.Contains("STUDIO_PYTHON_EXECUTABLE is Worker-only")) `
        -Message "Server Python rejection should identify the offending variable."

    $serverArtifactEnvironment = New-BaseEnvironment
    $serverArtifactEnvironment.STUDIO_SCRIPT_ARTIFACT_MAX_BYTES = "1024"
    $serverArtifactResult = Invoke-DeploymentPreflight -Role Server -Environment $serverArtifactEnvironment
    Assert-True -Condition ($serverArtifactResult.ExitCode -ne 0) -Message "Server artifact settings must fail preflight."
    Assert-True -Condition ($serverArtifactResult.Output.Contains("STUDIO_SCRIPT_ARTIFACT_MAX_BYTES is Worker-only")) `
        -Message "Server artifact rejection should identify the offending variable."

    $workerEnvironment = New-BaseEnvironment
    $workerEnvironment.STUDIO_CLUSTER_CODE = "DEFAULT-LOCAL"
    $workerEnvironment.STUDIO_AGGREGATION_HOME = $completeHome
    $workerEnvironment.STUDIO_PLUGIN_FINGERPRINT = "test-plugin-set-v1"
    $workerResult = Invoke-DeploymentPreflight -Role Worker -Environment $workerEnvironment
    Assert-True -Condition ($workerResult.ExitCode -eq 0) -Message "Valid Worker environment should pass preflight."

    $flinkEnvironment = New-BaseEnvironment
    $flinkResult = Invoke-DeploymentPreflight -Role Flink -Environment $flinkEnvironment
    Assert-True -Condition ($flinkResult.ExitCode -eq 0) -Message "Valid Flink planning environment should pass preflight."

    $flinkWorkerIdentityEnvironment = New-BaseEnvironment
    $flinkWorkerIdentityEnvironment.STUDIO_CLUSTER_CODE = "DEFAULT-LOCAL"
    $flinkWorkerIdentityResult = Invoke-DeploymentPreflight -Role Flink -Environment $flinkWorkerIdentityEnvironment
    Assert-True -Condition ($flinkWorkerIdentityResult.ExitCode -ne 0) `
        -Message "Flink planning service must reject Worker cluster identity."
    Assert-True -Condition ($flinkWorkerIdentityResult.Output.Contains("STUDIO_CLUSTER_CODE is Worker-only")) `
        -Message "Flink Worker identity rejection should identify the offending variable."

    $flinkGatewayEnvironment = New-BaseEnvironment
    $flinkGatewayEnvironment.STUDIO_GATEWAY_TRUST_ENABLED = "true"
    $flinkGatewayEnvironment.STUDIO_GATEWAY_SHARED_SECRET = "non-default-gateway-secret"
    $flinkGatewayResult = Invoke-DeploymentPreflight -Role Flink -Environment $flinkGatewayEnvironment
    Assert-True -Condition ($flinkGatewayResult.ExitCode -ne 0) `
        -Message "Flink planning service must reject trusted gateway identity exchange."
    Assert-True -Condition ($flinkGatewayResult.Output.Contains("Flink must set STUDIO_GATEWAY_TRUST_ENABLED=false")) `
        -Message "Flink gateway rejection should explain the role boundary."

    $incompleteHome = Join-Path $temporaryRoot "incomplete-aggregation-home"
    [void](New-Item -ItemType Directory -Path $incompleteHome -Force)
    $incompleteWorkerEnvironment = New-BaseEnvironment
    $incompleteWorkerEnvironment.STUDIO_CLUSTER_CODE = "DEFAULT-LOCAL"
    $incompleteWorkerEnvironment.STUDIO_AGGREGATION_HOME = $incompleteHome
    $incompleteResult = Invoke-DeploymentPreflight -Role Worker -Environment $incompleteWorkerEnvironment
    Assert-True -Condition ($incompleteResult.ExitCode -ne 0) -Message "Incomplete Worker plugin home must fail preflight."
    Assert-True -Condition ($incompleteResult.Output.Contains("Worker execution home is incomplete")) `
        -Message "Incomplete Worker rejection should explain the plugin-home failure."

    $lazyHome = Join-Path $temporaryRoot "lazy-aggregation-home"
    $lazyEnvironment = New-BaseEnvironment
    $lazyEnvironment.STUDIO_CLUSTER_CODE = "DEFAULT-LOCAL"
    $lazyEnvironment.STUDIO_AGGREGATION_HOME = $lazyHome
    $lazyEnvironment.STUDIO_RUNTIME_VERSION = "1.0_jdk17-SNAPSHOT"
    $lazyEnvironment.STUDIO_PLUGIN_RUNTIME_MODE = "LAZY_OBJECT_STORAGE"
    $lazyEnvironment.STUDIO_PLUGIN_PREFIX = "aggregation-plugins"
    $lazyEnvironment.STUDIO_PLUGIN_CHANNEL = "production"
    $lazyEnvironment.STUDIO_OBJECT_PROVIDER = "OSS"
    $lazyEnvironment.STUDIO_OBJECT_ENDPOINT = "https://oss.example.invalid"
    $lazyEnvironment.STUDIO_OBJECT_ACCESS_KEY = $secretMarkers[2]
    $lazyEnvironment.STUDIO_OBJECT_SECRET_KEY = $secretMarkers[3]
    $lazyEnvironment.STUDIO_PLUGIN_BUCKET = "test-plugin-bucket"
    $lazyResult = Invoke-DeploymentPreflight -Role Worker -Environment $lazyEnvironment
    Assert-True -Condition ($lazyResult.ExitCode -eq 0) -Message "Lazy OSS Worker environment should pass preflight."
    Assert-True -Condition (Test-Path -LiteralPath (Join-Path $lazyHome "cache") -PathType Container) `
        -Message "Lazy Worker preflight should initialize the writable cache root."

    $incompleteLazyEnvironment = $lazyEnvironment.Clone()
    [void]$incompleteLazyEnvironment.Remove("STUDIO_OBJECT_SECRET_KEY")
    $incompleteLazyResult = Invoke-DeploymentPreflight -Role Worker -Environment $incompleteLazyEnvironment
    Assert-True -Condition ($incompleteLazyResult.ExitCode -ne 0) -Message "Lazy Worker must reject incomplete OSS configuration."
    Assert-True -Condition ($incompleteLazyResult.Output.Contains("STUDIO_OBJECT_SECRET_KEY")) `
        -Message "Lazy Worker OSS rejection should identify the missing variable."

    $ossEnvironment = New-BaseEnvironment
    $ossEnvironment.STUDIO_CLUSTER_CODE = "DEFAULT-LOCAL"
    $ossEnvironment.STUDIO_AGGREGATION_HOME = $completeHome
    $ossEnvironment.STUDIO_PLUGIN_FINGERPRINT = "test-plugin-set-v1"
    $ossEnvironment.STUDIO_RUN_LOG_STORAGE_TYPE = "OBJECT_STORAGE"
    $ossEnvironment.STUDIO_OBJECT_PROVIDER = "ALIYUN_OSS"
    $ossEnvironment.STUDIO_OBJECT_ENDPOINT = "https://oss.example.invalid"
    $ossEnvironment.STUDIO_OBJECT_ACCESS_KEY = $secretMarkers[2]
    $ossEnvironment.STUDIO_OBJECT_SECRET_KEY = $secretMarkers[3]
    $ossEnvironment.STUDIO_OBJECT_BUCKET = "test-runtime-bucket"
    $ossResult = Invoke-DeploymentPreflight -Role Worker -Environment $ossEnvironment -RequireSharedObjectStorage
    Assert-True -Condition ($ossResult.ExitCode -eq 0) -Message "Complete OSS Worker environment should pass preflight."

    $missingOssEnvironment = $ossEnvironment.Clone()
    [void]$missingOssEnvironment.Remove("STUDIO_OBJECT_SECRET_KEY")
    $missingOssResult = Invoke-DeploymentPreflight -Role Worker -Environment $missingOssEnvironment -RequireSharedObjectStorage
    Assert-True -Condition ($missingOssResult.ExitCode -ne 0) -Message "Incomplete OSS settings must fail preflight."
    Assert-True -Condition ($missingOssResult.Output.Contains("STUDIO_OBJECT_SECRET_KEY")) `
        -Message "Incomplete OSS rejection should identify the missing variable name."

    Write-Host "Studio deployment preflight tests passed (12/12)."
} finally {
    if (Test-Path -LiteralPath $temporaryRoot) {
        $verifiedRoot = [IO.Path]::GetFullPath($temporaryRoot)
        if ($verifiedRoot.StartsWith($temporaryBase, [StringComparison]::OrdinalIgnoreCase) -and
                [IO.Path]::GetFileName($verifiedRoot).StartsWith("studio-deployment-preflight-test-", [StringComparison]::Ordinal)) {
            Remove-Item -LiteralPath $verifiedRoot -Recurse -Force
        }
    }
}
