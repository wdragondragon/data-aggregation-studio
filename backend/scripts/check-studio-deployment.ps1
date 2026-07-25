param(
    [Parameter(Mandatory = $true)]
    [ValidateSet("Server", "Worker", "Flink")]
    [string]$Role,
    [switch]$RequireSharedObjectStorage
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$errors = [System.Collections.Generic.List[string]]::new()
$warnings = [System.Collections.Generic.List[string]]::new()

function Get-ProcessEnvironmentValue {
    param([Parameter(Mandatory = $true)][string]$Name)
    return [Environment]::GetEnvironmentVariable($Name, [EnvironmentVariableTarget]::Process)
}

function Has-Text {
    param([AllowNull()][string]$Value)
    return $null -ne $Value -and -not [string]::IsNullOrWhiteSpace($Value)
}

function Add-DeploymentError {
    param([Parameter(Mandatory = $true)][string]$Message)
    $errors.Add($Message)
}

function Add-DeploymentWarning {
    param([Parameter(Mandatory = $true)][string]$Message)
    $warnings.Add($Message)
}

function Require-EnvironmentValue {
    param([Parameter(Mandatory = $true)][string]$Name)
    $value = Get-ProcessEnvironmentValue -Name $Name
    if (-not (Has-Text -Value $value)) {
        Add-DeploymentError "$Name must be injected before starting Studio $Role."
    }
    return $value
}

function Assert-NonDefaultSecret {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string[]]$RejectedValues
    )
    $value = Require-EnvironmentValue -Name $Name
    if (Has-Text -Value $value) {
        $normalized = $value.Trim()
        if ($RejectedValues -contains $normalized) {
            Add-DeploymentError "$Name must not use a repository or historical default value."
        }
    }
}

function Require-ObjectStorageValue {
    param(
        [Parameter(Mandatory = $true)][string]$PrimaryName,
        [Parameter(Mandatory = $true)][string]$FallbackName
    )
    $primary = Get-ProcessEnvironmentValue -Name $PrimaryName
    $fallback = Get-ProcessEnvironmentValue -Name $FallbackName
    if (-not (Has-Text -Value $primary) -and -not (Has-Text -Value $fallback)) {
        Add-DeploymentError "$PrimaryName (or compatibility variable $FallbackName) is required for shared object storage."
    }
}

foreach ($deprecatedName in @(
        "STUDIO_RUNTIME_CLUSTER_MODE",
        "STUDIO_RUNTIME_CLUSTER_BACKFILL_ENABLED",
        "STUDIO_RUNTIME_CLUSTER_BACKFILL_DRY_RUN")) {
    if (Has-Text -Value (Get-ProcessEnvironmentValue -Name $deprecatedName)) {
        Add-DeploymentError "$deprecatedName is obsolete and must be removed from the steady-state deployment."
    }
}

Assert-NonDefaultSecret -Name "STUDIO_INTERNAL_API_TOKEN" -RejectedValues @(
    "studio-internal-token", "api_token")
Assert-NonDefaultSecret -Name "STUDIO_ENCRYPTION_SECRET" -RejectedValues @(
    "studio-secret-key", "secret-key")

$gatewayTrust = Get-ProcessEnvironmentValue -Name "STUDIO_GATEWAY_TRUST_ENABLED"
if (-not (Has-Text -Value $gatewayTrust)) {
    Add-DeploymentError "STUDIO_GATEWAY_TRUST_ENABLED must be set explicitly to true or false."
} elseif ($gatewayTrust.Trim() -notin @("true", "false")) {
    Add-DeploymentError "STUDIO_GATEWAY_TRUST_ENABLED must be true or false."
} elseif ($Role -in @("Worker", "Flink") -and $gatewayTrust.Trim() -ne "false") {
    Add-DeploymentError "$Role must set STUDIO_GATEWAY_TRUST_ENABLED=false."
} elseif ($gatewayTrust.Trim() -eq "true") {
    Assert-NonDefaultSecret -Name "STUDIO_GATEWAY_SHARED_SECRET" -RejectedValues @(
        "change-me", "studio-gateway-secret")
}

if ($Role -in @("Server", "Flink")) {
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
        if (Has-Text -Value (Get-ProcessEnvironmentValue -Name $workerOnlyName)) {
            Add-DeploymentError "$workerOnlyName is Worker-only and must not be configured for Studio $Role."
        }
    }
} else {
    Require-EnvironmentValue -Name "STUDIO_CLUSTER_CODE" | Out-Null
    $aggregationHomeValue = Require-EnvironmentValue -Name "STUDIO_AGGREGATION_HOME"
    if (Has-Text -Value $aggregationHomeValue) {
        try {
            $aggregationHome = [IO.Path]::GetFullPath($aggregationHomeValue.Trim())
            if (-not (Test-Path -LiteralPath $aggregationHome -PathType Container)) {
                Add-DeploymentError "STUDIO_AGGREGATION_HOME must point to an existing directory."
            } else {
                $requiredPaths = @(
                    "conf/core.json",
                    "plugin/source",
                    "plugin/reader",
                    "plugin/writer",
                    "plugin/transformer"
                )
                foreach ($relativePath in $requiredPaths) {
                    $candidate = Join-Path $aggregationHome $relativePath
                    if (-not (Test-Path -LiteralPath $candidate)) {
                        Add-DeploymentError "Worker execution home is incomplete; missing $relativePath."
                    }
                }
            }
        } catch {
            Add-DeploymentError "STUDIO_AGGREGATION_HOME is not a valid filesystem path."
        }
    }
    if (-not (Has-Text -Value (Get-ProcessEnvironmentValue -Name "STUDIO_PLUGIN_FINGERPRINT"))) {
        Add-DeploymentWarning "STUDIO_PLUGIN_FINGERPRINT is empty; production cannot prove that Worker plugin manifests are identical."
    }
}

if ($RequireSharedObjectStorage) {
    if ($Role -eq "Flink") {
        Add-DeploymentError "Shared run-log object storage is a Server/Worker concern and must not be assigned to studio-flink."
    }
    $storageType = Get-ProcessEnvironmentValue -Name "STUDIO_RUN_LOG_STORAGE_TYPE"
    if (-not (Has-Text -Value $storageType) -or $storageType.Trim() -ne "OBJECT_STORAGE") {
        Add-DeploymentError "STUDIO_RUN_LOG_STORAGE_TYPE=OBJECT_STORAGE is required for multi-cluster production."
    }
    $storageProvider = Get-ProcessEnvironmentValue -Name "STUDIO_OBJECT_PROVIDER"
    if (-not (Has-Text -Value $storageProvider)) {
        $storageProvider = Get-ProcessEnvironmentValue -Name "STUDIO_RUN_LOG_OBJECT_PROVIDER"
    }
    if (-not (Has-Text -Value $storageProvider)) {
        Add-DeploymentError "STUDIO_OBJECT_PROVIDER (or compatibility variable STUDIO_RUN_LOG_OBJECT_PROVIDER) must explicitly select MINIO or OSS."
    } else {
        $normalizedProvider = $storageProvider.Trim().ToUpperInvariant()
        if ($normalizedProvider -in @("ALIYUN", "ALIYUN_OSS", "ALIYUN-OSS")) {
            $normalizedProvider = "OSS"
        }
        if ($normalizedProvider -notin @("MINIO", "OSS")) {
            Add-DeploymentError "Object storage provider must be MINIO or OSS."
        }
    }
    Require-ObjectStorageValue -PrimaryName "STUDIO_OBJECT_ENDPOINT" -FallbackName "STUDIO_RUN_LOG_OBJECT_ENDPOINT"
    Require-ObjectStorageValue -PrimaryName "STUDIO_OBJECT_ACCESS_KEY" -FallbackName "STUDIO_RUN_LOG_OBJECT_ACCESS_KEY"
    Require-ObjectStorageValue -PrimaryName "STUDIO_OBJECT_SECRET_KEY" -FallbackName "STUDIO_RUN_LOG_OBJECT_SECRET_KEY"
    Require-ObjectStorageValue -PrimaryName "STUDIO_OBJECT_BUCKET" -FallbackName "STUDIO_RUN_LOG_OBJECT_BUCKET"
}

foreach ($warning in $warnings) {
    Write-Warning $warning
}
if ($errors.Count -gt 0) {
    foreach ($errorMessage in $errors) {
        Write-Error $errorMessage -ErrorAction Continue
    }
    Write-Host "Studio $Role deployment preflight failed with $($errors.Count) error(s)." -ForegroundColor Red
    exit 1
}

Write-Host "Studio $Role deployment preflight passed." -ForegroundColor Green
if ($RequireSharedObjectStorage) {
    Write-Host "Shared object storage environment is present. Connectivity, capacity, lifecycle, and HA still require target-environment verification."
}
