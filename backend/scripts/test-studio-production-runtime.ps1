[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet("RuntimeEndpoint", "Network", "ObjectStorage")]
    [string[]]$Checks,
    [ValidateSet("None", "Allowed", "Denied")]
    [string]$NetworkExpectation = "None",
    [Parameter(Mandatory = $true)]
    [ValidateSet("OMS", "WORKER", "RELEASE_RUNNER")]
    [string]$NodeRole,
    [string]$ResultDirectory,
    [string]$NodeLabel = "unspecified",
    [int]$TimeoutSeconds = 5,
    [switch]$RequireComplete
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
Add-Type -AssemblyName System.Net.Http

$results = [System.Collections.Generic.List[object]]::new()
$secretValues = [System.Collections.Generic.List[string]]::new()
$script:runtimeHeaderNames = @()
$script:probeStage = "NOT_STARTED"
$script:reportRuntimeClusterId = $null

function Get-ProcessEnvironmentValue {
    param([Parameter(Mandatory = $true)][string]$Name)
    return [Environment]::GetEnvironmentVariable($Name, [EnvironmentVariableTarget]::Process)
}

function Has-Text {
    param([AllowNull()][string]$Value)
    return $null -ne $Value -and -not [string]::IsNullOrWhiteSpace($Value)
}

function Test-EvidenceId {
    param([AllowNull()][string]$Value)
    if (-not (Has-Text -Value $Value)) {
        return $false
    }
    $trimmed = $Value.Trim()
    return $trimmed -match '^[A-Za-z0-9][A-Za-z0-9._:/-]{2,127}$' -and
            $trimmed -notmatch '^[A-Za-z][A-Za-z0-9+.-]*://'
}

function Require-EnvironmentValue {
    param([Parameter(Mandatory = $true)][string]$Name)
    $value = Get-ProcessEnvironmentValue -Name $Name
    if (-not (Has-Text -Value $value)) {
        throw "$Name is required for the selected acceptance check."
    }
    return $value.Trim()
}

function Get-AcceptanceInteger {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][int]$DefaultValue,
        [Parameter(Mandatory = $true)][int]$Minimum,
        [Parameter(Mandatory = $true)][int]$Maximum
    )
    $value = Get-ProcessEnvironmentValue -Name $Name
    if (-not (Has-Text -Value $value)) {
        return $DefaultValue
    }
    $parsed = 0
    if (-not [int]::TryParse($value.Trim(), [ref]$parsed) -or $parsed -lt $Minimum -or $parsed -gt $Maximum) {
        throw "$Name must be an integer between $Minimum and $Maximum."
    }
    return $parsed
}

function Add-SecretValue {
    param([AllowNull()][string]$Value)
    if (-not (Has-Text -Value $Value)) {
        return
    }
    $normalized = $Value.Trim()
    if ($normalized.Length -lt 4) {
        throw "Acceptance credentials must contain at least four characters for leak detection."
    }
    if (-not $secretValues.Contains($normalized)) {
        [void]$secretValues.Add($normalized)
    }
}

function Get-Sha256Prefix {
    param([Parameter(Mandatory = $true)][string]$Value)
    $sha256 = [Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [Text.Encoding]::UTF8.GetBytes($Value)
        $hash = $sha256.ComputeHash($bytes)
        return ([BitConverter]::ToString($hash).Replace("-", "").ToLowerInvariant()).Substring(0, 12)
    } finally {
        $sha256.Dispose()
    }
}

function Get-SafeUriSummary {
    param([Parameter(Mandatory = $true)][Uri]$Uri)
    $portText = ""
    if (-not $Uri.IsDefaultPort) {
        $portText = ":$($Uri.Port)"
    }
    return "$($Uri.Scheme)://host-sha256:$((Get-Sha256Prefix -Value $Uri.DnsSafeHost))$portText$($Uri.AbsolutePath)"
}

function Get-SafeHostSummary {
    param(
        [Parameter(Mandatory = $true)][string]$HostName,
        [Parameter(Mandatory = $true)][int]$Port
    )
    return "host-sha256:$((Get-Sha256Prefix -Value $HostName)):$Port"
}

function Add-AcceptanceResult {
    param(
        [Parameter(Mandatory = $true)][string]$Id,
        [Parameter(Mandatory = $true)][ValidateSet("PASS", "FAIL", "PENDING")][string]$Status,
        [Parameter(Mandatory = $true)][string]$Summary,
        [hashtable]$Details = @{}
    )
    [void]$results.Add([ordered]@{
        id = $Id
        status = $Status
        summary = $Summary
        details = $Details
    })
}

function Assert-NoSecretText {
    param(
        [AllowNull()][string]$Text,
        [Parameter(Mandatory = $true)][string]$Context
    )
    if ($null -eq $Text) {
        return
    }
    foreach ($secret in $secretValues) {
        if ($Text.IndexOf($secret, [StringComparison]::Ordinal) -ge 0) {
            throw "A configured secret was detected in $Context. The value was not written to the report."
        }
    }
}

function ConvertTo-TransportHeaders {
    $json = Require-EnvironmentValue -Name "STUDIO_ACCEPTANCE_RUNTIME_HEADERS_JSON"
    $parsed = $json | ConvertFrom-Json
    if ($null -eq $parsed -or $parsed -isnot [psobject]) {
        throw "STUDIO_ACCEPTANCE_RUNTIME_HEADERS_JSON must be a JSON object."
    }
    $headers = [ordered]@{}
    $blocked = @(
        "host", "content-length", "connection", "transfer-encoding", "keep-alive",
        "te", "trailer", "upgrade", "x-studio-internal-token", "x-studio-target-cluster-id"
    )
    foreach ($property in $parsed.PSObject.Properties) {
        $name = [string]$property.Name
        if (-not (Has-Text -Value $name) -or $name -notmatch '^[!#$%&''*+\-.^_`|~0-9A-Za-z]+$') {
            throw "Runtime transport header JSON contains an invalid header name."
        }
        if ($blocked -contains $name.Trim().ToLowerInvariant() -or $name.StartsWith("X-Studio-", [StringComparison]::OrdinalIgnoreCase)) {
            throw "Runtime transport headers must not override HTTP framing or Studio internal headers."
        }
        $rawValues = @($property.Value)
        if ($rawValues.Count -eq 0) {
            throw "Runtime transport headers must not contain an empty value list."
        }
        $values = [System.Collections.Generic.List[string]]::new()
        foreach ($rawValue in $rawValues) {
            if ($null -eq $rawValue -or $rawValue -isnot [string] -or -not (Has-Text -Value $rawValue)) {
                throw "Runtime transport header values must be non-empty strings."
            }
            $value = ([string]$rawValue).Trim()
            if ($value.Contains("`r") -or $value.Contains("`n")) {
                throw "Runtime transport header values must not contain line breaks."
            }
            Add-SecretValue -Value $value
            [void]$values.Add($value)
        }
        $headers[$name] = $values.ToArray()
    }
    if ($headers.Count -eq 0) {
        throw "STUDIO_ACCEPTANCE_RUNTIME_HEADERS_JSON must contain the real SLB transport headers."
    }
    $script:runtimeHeaderNames = @($headers.Keys | Sort-Object)
    return $headers
}

function Get-ResponseHeaderValues {
    param(
        [Parameter(Mandatory = $true)][Net.Http.HttpResponseMessage]$Response,
        [Parameter(Mandatory = $true)][string]$Name
    )
    try {
        return @($Response.Headers.GetValues($Name))
    } catch {
        try {
            return @($Response.Content.Headers.GetValues($Name))
        } catch {
            return @()
        }
    }
}

function Read-BoundedResponseBody {
    param(
        [Parameter(Mandatory = $true)][Net.Http.HttpResponseMessage]$Response,
        [int]$MaxBytes = 16384,
        [int]$ReadTimeoutSeconds = 5
    )
    if ($null -eq $Response.Content) {
        return ""
    }
    $stream = $Response.Content.ReadAsStreamAsync().GetAwaiter().GetResult()
    $output = [IO.MemoryStream]::new()
    try {
        $buffer = New-Object byte[] 4096
        $total = 0
        $deadline = [DateTime]::UtcNow.AddSeconds([Math]::Max(1, [Math]::Min($ReadTimeoutSeconds, 60)))
        while ($true) {
            $remaining = $deadline - [DateTime]::UtcNow
            if ($remaining.TotalMilliseconds -le 0) {
                throw "Runtime health response body timed out."
            }
            $readTask = $stream.ReadAsync($buffer, 0, $buffer.Length)
            if (-not $readTask.Wait($remaining)) {
                throw "Runtime health response body timed out."
            }
            $read = $readTask.GetAwaiter().GetResult()
            if ($read -le 0) {
                break
            }
            $total += $read
            if ($total -gt $MaxBytes) {
                throw "Runtime health response exceeded the 16 KB acceptance limit."
            }
            $output.Write($buffer, 0, $read)
        }
        return [Text.Encoding]::UTF8.GetString($output.ToArray())
    } finally {
        $output.Dispose()
        $stream.Dispose()
    }
}

function Invoke-RuntimeHealthRequest {
    param(
        [Parameter(Mandatory = $true)][Uri]$HealthUri,
        [Parameter(Mandatory = $true)][string]$InternalToken,
        [Parameter(Mandatory = $true)][string]$ClusterId,
        [Parameter(Mandatory = $true)][System.Collections.IDictionary]$TransportHeaders,
        [Parameter(Mandatory = $true)][Net.Http.HttpClient]$Client
    )
    $request = [Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::Get, $HealthUri)
    $response = $null
    $stopwatch = [Diagnostics.Stopwatch]::StartNew()
    try {
        $script:probeStage = "ADD_HEADERS"
        foreach ($name in $TransportHeaders.Keys) {
            foreach ($value in @($TransportHeaders[$name])) {
                if (-not $request.Headers.TryAddWithoutValidation([string]$name, [string]$value)) {
                    throw "The HTTP client rejected a configured runtime transport header."
                }
            }
        }
        [void]$request.Headers.TryAddWithoutValidation("X-Studio-Internal-Token", $InternalToken)
        [void]$request.Headers.TryAddWithoutValidation("X-Studio-Target-Cluster-Id", $ClusterId)
        $script:probeStage = "SEND"
        $response = $Client.SendAsync($request, [Net.Http.HttpCompletionOption]::ResponseHeadersRead).GetAwaiter().GetResult()
        $script:probeStage = "READ_BODY"
        $body = Read-BoundedResponseBody -Response $response -ReadTimeoutSeconds $TimeoutSeconds
        $script:probeStage = "SCAN_RESPONSE"
        $headerLines = @($response.Headers | ForEach-Object { "{0}:{1}" -f $_.Key, ($_.Value -join ",") })
        if ($null -ne $response.Content) {
            $headerLines += @($response.Content.Headers | ForEach-Object { "{0}:{1}" -f $_.Key, ($_.Value -join ",") })
        }
        $headerText = $headerLines -join "`n"
        Assert-NoSecretText -Text $body -Context "runtime response body"
        Assert-NoSecretText -Text $headerText -Context "runtime response headers"
        $script:probeStage = "EXTRACT_MARKERS"
        $stopwatch.Stop()
        return [ordered]@{
            StatusCode = [int]$response.StatusCode
            RuntimeMarkers = @(Get-ResponseHeaderValues -Response $response -Name "X-Studio-Runtime-Response")
            InternalErrors = @(Get-ResponseHeaderValues -Response $response -Name "X-Studio-Internal-Error")
            DurationMs = [Math]::Round($stopwatch.Elapsed.TotalMilliseconds, 3)
        }
    } finally {
        $stopwatch.Stop()
        if ($null -ne $response) {
            $response.Dispose()
        }
        $request.Dispose()
    }
}

function New-RuntimeAcceptanceHttpClient {
    $script:probeStage = "CREATE_CLIENT"
    $handler = [Net.Http.HttpClientHandler]::new()
    $handler.AllowAutoRedirect = $false
    $handler.UseCookies = $false
    $client = [Net.Http.HttpClient]::new($handler)
    $client.Timeout = [TimeSpan]::FromSeconds([Math]::Max(1, [Math]::Min($TimeoutSeconds, 60)))
    return $client
}

function Test-AuthenticatedRuntimeResponse {
    param([Parameter(Mandatory = $true)][System.Collections.IDictionary]$Response)
    return [int]$Response.StatusCode -ge 200 -and [int]$Response.StatusCode -lt 300 -and
            @($Response.RuntimeMarkers | Where-Object { $_ -eq "AUTHENTICATED" }).Count -gt 0
}

function Get-NearestRankPercentile {
    param(
        [Parameter(Mandatory = $true)][double[]]$Values,
        [Parameter(Mandatory = $true)][ValidateRange(0.0, 1.0)][double]$Percentile
    )
    if ($Values.Count -eq 0) {
        throw "At least one runtime performance sample is required."
    }
    $sorted = @($Values | Sort-Object)
    $index = [Math]::Max(0, [Math]::Ceiling($sorted.Count * $Percentile) - 1)
    return [Math]::Round([double]$sorted[$index], 3)
}

function Invoke-RuntimeEndpointAcceptance {
    $stage = "INPUT"
    $client = $null
    try {
        $baseUrl = Require-EnvironmentValue -Name "STUDIO_ACCEPTANCE_RUNTIME_BASE_URL"
        $baseUri = [Uri]$baseUrl
        if (-not $baseUri.IsAbsoluteUri -or (Has-Text -Value $baseUri.UserInfo) -or
                (Has-Text -Value $baseUri.Query) -or (Has-Text -Value $baseUri.Fragment)) {
            throw "STUDIO_ACCEPTANCE_RUNTIME_BASE_URL must be an absolute base URL without credentials, query, or fragment."
        }
        $allowHttp = (Get-ProcessEnvironmentValue -Name "STUDIO_ACCEPTANCE_ALLOW_HTTP")
        if ($baseUri.Scheme -ne "https" -and $allowHttp -ne "true") {
            throw "Production runtime acceptance requires HTTPS unless STUDIO_ACCEPTANCE_ALLOW_HTTP=true is explicitly set for an isolated test."
        }
        if ($baseUri.Scheme -notin @("http", "https")) {
            throw "Runtime acceptance supports HTTP or HTTPS endpoints only."
        }
        $healthUri = [Uri]($baseUri.AbsoluteUri.TrimEnd("/") + "/internal/runtime/health")
        $clusterId = Require-EnvironmentValue -Name "STUDIO_ACCEPTANCE_RUNTIME_CLUSTER_ID"
        if ($clusterId -notmatch '^\d+$') {
            throw "STUDIO_ACCEPTANCE_RUNTIME_CLUSTER_ID must be a numeric database ID."
        }
        $internalToken = Require-EnvironmentValue -Name "STUDIO_INTERNAL_API_TOKEN"
        Add-SecretValue -Value $internalToken
        $transportHeaders = ConvertTo-TransportHeaders
        $performanceSampleCount = Get-AcceptanceInteger -Name "STUDIO_ACCEPTANCE_RUNTIME_PERFORMANCE_SAMPLES" `
                -DefaultValue 20 -Minimum 5 -Maximum 200
        $performanceMaxP95Ms = Get-AcceptanceInteger -Name "STUDIO_ACCEPTANCE_RUNTIME_MAX_P95_MS" `
                -DefaultValue 1000 -Minimum 1 -Maximum 60000
        $durations = [System.Collections.Generic.List[double]]::new()
        $client = New-RuntimeAcceptanceHttpClient

        $stage = "AUTHENTICATED_PROBE"
        $authenticated = Invoke-RuntimeHealthRequest -HealthUri $healthUri -InternalToken $internalToken `
                -ClusterId $clusterId -TransportHeaders $transportHeaders -Client $client
        if (-not (Test-AuthenticatedRuntimeResponse -Response $authenticated)) {
            throw "The real runtime health request did not return a marked 2xx Worker response."
        }
        [void]$durations.Add([double]$authenticated.DurationMs)
        $invalidToken = "studio-acceptance-invalid-$([Guid]::NewGuid().ToString('N'))"
        $stage = "REJECTED_PROBE"
        $rejected = Invoke-RuntimeHealthRequest -HealthUri $healthUri -InternalToken $invalidToken `
                -ClusterId $clusterId -TransportHeaders $transportHeaders -Client $client

        $stage = "PROTOCOL_VALIDATION"
        $internalErrorMarker = @($rejected.InternalErrors | Where-Object { $_ -eq "INTERNAL_AUTHENTICATION" }).Count -gt 0
        $rejectedHasRuntimeMarker = @($rejected.RuntimeMarkers | Where-Object { $_ -eq "AUTHENTICATED" }).Count -gt 0
        if ($rejected.StatusCode -ne 401 -or -not $internalErrorMarker -or $rejectedHasRuntimeMarker) {
            throw "The invalid internal token request was not isolated with the expected Worker authentication marker."
        }

        $stage = "PERFORMANCE_BASELINE"
        for ($sampleIndex = 1; $sampleIndex -lt $performanceSampleCount; $sampleIndex++) {
            $sample = Invoke-RuntimeHealthRequest -HealthUri $healthUri -InternalToken $internalToken `
                    -ClusterId $clusterId -TransportHeaders $transportHeaders -Client $client
            if (-not (Test-AuthenticatedRuntimeResponse -Response $sample)) {
                throw "A runtime performance sample did not return a marked 2xx Worker response."
            }
            [void]$durations.Add([double]$sample.DurationMs)
        }
        $performanceP50Ms = Get-NearestRankPercentile -Values $durations.ToArray() -Percentile 0.50
        $performanceP95Ms = Get-NearestRankPercentile -Values $durations.ToArray() -Percentile 0.95
        $performanceP99Ms = Get-NearestRankPercentile -Values $durations.ToArray() -Percentile 0.99
        $performanceMaxMs = [Math]::Round([double](($durations | Measure-Object -Maximum).Maximum), 3)
        if ($performanceP95Ms -gt $performanceMaxP95Ms) {
            throw "Runtime endpoint p95 exceeded the configured acceptance threshold."
        }
        Add-AcceptanceResult -Id "runtime-endpoint-protocol" -Status "PASS" `
                -Summary "The SLB endpoint preserved Worker authentication markers and isolated internal authentication failures." `
                -Details ([ordered]@{
                    endpoint = Get-SafeUriSummary -Uri $healthUri
                    targetClusterId = $clusterId
                    transportHeaderNames = $script:runtimeHeaderNames
                    authenticatedStatus = $authenticated.StatusCode
                    rejectedStatus = $rejected.StatusCode
                    redirectsAllowed = $false
                    responseBodyLimitBytes = 16384
                    secretLeakScan = "PASS"
                    performanceSampleCount = $performanceSampleCount
                    performanceP50Ms = $performanceP50Ms
                    performanceP95Ms = $performanceP95Ms
                    performanceP99Ms = $performanceP99Ms
                    performanceMaxMs = $performanceMaxMs
                    performanceMaxP95Ms = $performanceMaxP95Ms
                    performanceErrors = 0
                })
    } catch {
        Add-AcceptanceResult -Id "runtime-endpoint-protocol" -Status "FAIL" `
                -Summary "Runtime endpoint acceptance failed without recording the endpoint, credentials, or response body." `
                -Details ([ordered]@{ stage = $stage; probeStage = $script:probeStage; errorType = $_.Exception.GetType().Name; transportHeaderNames = $script:runtimeHeaderNames })
    } finally {
        if ($null -ne $client) {
            $client.Dispose()
        }
    }
}

function Test-TcpReachability {
    param(
        [Parameter(Mandatory = $true)][string]$HostName,
        [Parameter(Mandatory = $true)][int]$Port
    )
    $client = [Net.Sockets.TcpClient]::new()
    try {
        $task = $client.ConnectAsync($HostName, $Port)
        if (-not $task.Wait([TimeSpan]::FromSeconds([Math]::Max(1, [Math]::Min($TimeoutSeconds, 60))))) {
            return $false
        }
        return $client.Connected
    } catch {
        return $false
    } finally {
        $client.Dispose()
    }
}

function Invoke-NetworkAcceptance {
    try {
        if ($NetworkExpectation -eq "None") {
            throw "Network checks require -NetworkExpectation Allowed or Denied."
        }
        $hostName = Require-EnvironmentValue -Name "STUDIO_ACCEPTANCE_DATASOURCE_HOST"
        $portText = Require-EnvironmentValue -Name "STUDIO_ACCEPTANCE_DATASOURCE_PORT"
        $port = 0
        if (-not [int]::TryParse($portText, [ref]$port) -or $port -lt 1 -or $port -gt 65535) {
            throw "STUDIO_ACCEPTANCE_DATASOURCE_PORT must be a valid TCP port."
        }
        $reachable = Test-TcpReachability -HostName $hostName -Port $port
        $expectedReachable = $NetworkExpectation -eq "Allowed"
        if ($reachable -ne $expectedReachable) {
            throw "The observed data source reachability did not match the requested node expectation."
        }
        Add-AcceptanceResult -Id "datasource-network-boundary" -Status "PASS" `
                -Summary "The node data source TCP reachability matched the declared control-plane or Worker boundary." `
                -Details ([ordered]@{
                    target = Get-SafeHostSummary -HostName $hostName -Port $port
                    runtimeClusterId = $script:reportRuntimeClusterId
                    expectation = $NetworkExpectation.ToUpperInvariant()
                    observed = $(if ($reachable) { "REACHABLE" } else { "UNREACHABLE" })
                })
    } catch {
        Add-AcceptanceResult -Id "datasource-network-boundary" -Status "FAIL" `
                -Summary "Data source network boundary acceptance failed without recording the target address." `
                -Details ([ordered]@{ expectation = $NetworkExpectation.ToUpperInvariant(); errorType = $_.Exception.GetType().Name })
    }
}

function Get-ManualAttestationStatus {
    param(
        [Parameter(Mandatory = $true)][string]$StatusEnvironmentName,
        [Parameter(Mandatory = $true)][string]$EvidenceEnvironmentName
    )
    $statusValue = Get-ProcessEnvironmentValue -Name $StatusEnvironmentName
    if (-not (Has-Text -Value $statusValue)) {
        return [ordered]@{ Status = "PENDING"; EvidenceId = $null; Reason = "REQUIRED" }
    }
    if ($statusValue.Trim().ToUpperInvariant() -ne "PASS") {
        return [ordered]@{ Status = "FAIL"; EvidenceId = $null; Reason = "INVALID_STATUS" }
    }
    $evidenceId = Get-ProcessEnvironmentValue -Name $EvidenceEnvironmentName
    if (-not (Test-EvidenceId -Value $evidenceId)) {
        return [ordered]@{ Status = "FAIL"; EvidenceId = $null; Reason = "EVIDENCE_ID_REQUIRED" }
    }
    return [ordered]@{ Status = "PASS"; EvidenceId = $evidenceId.Trim(); Reason = "OPERATOR_ATTESTED" }
}

function Invoke-ObjectStorageAcceptance {
    $temporaryRoot = $null
    $savedEnvironment = @{}
    $reportDirectory = $null
    $reportSuffix = $null
    $stage = "INPUT"
    try {
        $providerValue = Require-EnvironmentValue -Name "STUDIO_ACCEPTANCE_OBJECT_PROVIDER"
        $provider = $providerValue.ToUpperInvariant()
        if ($provider -in @("ALIYUN", "ALIYUN_OSS", "ALIYUN-OSS")) {
            $provider = "OSS"
        }
        if ($provider -notin @("MINIO", "OSS")) {
            throw "STUDIO_ACCEPTANCE_OBJECT_PROVIDER must be MINIO or OSS."
        }
        $endpoint = Require-EnvironmentValue -Name "STUDIO_ACCEPTANCE_OBJECT_ENDPOINT"
        $accessKey = Require-EnvironmentValue -Name "STUDIO_ACCEPTANCE_OBJECT_ACCESS_KEY"
        $secretKey = Require-EnvironmentValue -Name "STUDIO_ACCEPTANCE_OBJECT_SECRET_KEY"
        $bucket = Require-EnvironmentValue -Name "STUDIO_ACCEPTANCE_OBJECT_BUCKET"
        $regionValue = Get-ProcessEnvironmentValue -Name "STUDIO_ACCEPTANCE_OBJECT_REGION"
        $region = $(if (Has-Text -Value $regionValue) { $regionValue.Trim() } else { "" })
        $createBucketValue = Get-ProcessEnvironmentValue -Name "STUDIO_ACCEPTANCE_OBJECT_CREATE_BUCKET"
        $createBucket = "false"
        if (Has-Text -Value $createBucketValue) {
            $createBucket = $createBucketValue.Trim().ToLowerInvariant()
            if ($createBucket -notin @("true", "false")) {
                throw "STUDIO_ACCEPTANCE_OBJECT_CREATE_BUCKET must be true or false."
            }
        }
        Add-SecretValue -Value $accessKey
        Add-SecretValue -Value $secretKey
        $endpointUri = [Uri]$endpoint
        if (-not $endpointUri.IsAbsoluteUri -or $endpointUri.Scheme -notin @("http", "https") -or
                (Has-Text -Value $endpointUri.UserInfo)) {
            throw "STUDIO_ACCEPTANCE_OBJECT_ENDPOINT must be an absolute HTTP(S) URL without credentials."
        }

        $temporaryBase = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
        $stage = "TEMPORARY_DIRECTORY"
        $temporaryRoot = [IO.Path]::GetFullPath((Join-Path $temporaryBase ("studio-runtime-acceptance-" + [Guid]::NewGuid().ToString("N"))))
        if (-not $temporaryRoot.StartsWith($temporaryBase, [StringComparison]::OrdinalIgnoreCase)) {
            throw "Unable to allocate a verified acceptance temporary directory."
        }
        [void](New-Item -ItemType Directory -Path $temporaryRoot -Force)
        $logPath = Join-Path $temporaryRoot "maven.log"

        $environmentMapping = [ordered]@{
            STUDIO_IT_OBJECT_PROVIDER = $provider
            STUDIO_IT_OBJECT_ENDPOINT = $endpoint
            STUDIO_IT_OBJECT_ACCESS_KEY = $accessKey
            STUDIO_IT_OBJECT_SECRET_KEY = $secretKey
            STUDIO_IT_OBJECT_BUCKET = $bucket
            STUDIO_IT_OBJECT_REGION = $region
            STUDIO_IT_OBJECT_CREATE_BUCKET = $createBucket
        }
        foreach ($entry in $environmentMapping.GetEnumerator()) {
            $savedEnvironment[$entry.Key] = Get-ProcessEnvironmentValue -Name $entry.Key
            [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, [EnvironmentVariableTarget]::Process)
        }
        $mavenCommand = Get-ProcessEnvironmentValue -Name "STUDIO_ACCEPTANCE_MAVEN_COMMAND"
        if (-not (Has-Text -Value $mavenCommand)) {
            $mavenCommand = "mvn"
        }
        $backendRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
        $reportDirectory = [IO.Path]::GetFullPath((Join-Path $backendRoot "studio-test\target\surefire-reports"))
        $reportSuffix = "studio-acceptance-" + [Guid]::NewGuid().ToString("N")
        $stage = "MAVEN_EXECUTION"
        Push-Location $backendRoot
        try {
            & $mavenCommand.Trim() -pl studio-test -am "-Dtest=SharedObjectStorageRuntimeIT" `
                    "-Dsurefire.failIfNoSpecifiedTests=false" "-Dsurefire.reportNameSuffix=$reportSuffix" test *> $logPath
            $mavenExitCode = $LASTEXITCODE
        } finally {
            Pop-Location
        }
        $stage = "REPORT_DISCOVERY"
        $testReport = Get-ChildItem -LiteralPath $reportDirectory `
                -Filter "TEST-com.jdragon.studio.test.SharedObjectStorageRuntimeIT-$reportSuffix.xml" `
                -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($mavenExitCode -ne 0 -or $null -eq $testReport) {
            throw "The shared object storage integration test did not complete successfully."
        }
        $stage = "REPORT_VALIDATION"
        [xml]$testXml = Get-Content -LiteralPath $testReport.FullName -Raw -Encoding UTF8
        $suite = $testXml.testsuite
        if ([int]$suite.tests -ne 1 -or [int]$suite.failures -ne 0 -or [int]$suite.errors -ne 0 -or [int]$suite.skipped -ne 0) {
            throw "The shared object storage integration test was not an executed 1/1 pass."
        }
        Add-AcceptanceResult -Id "object-storage-runtime-protocol" -Status "PASS" `
                -Summary "The isolated object storage test verified cross-node archive, read, cleanup, failure, and recovery behavior." `
                -Details ([ordered]@{
                    provider = $provider
                    endpoint = Get-SafeUriSummary -Uri $endpointUri
                    bucket = "bucket-sha256:$((Get-Sha256Prefix -Value $bucket))"
                    createBucket = [bool]::Parse($createBucket)
                    tests = 1
                    failures = 0
                    errors = 0
                    skipped = 0
                    temporaryObjectsCleanedByTest = $true
                })
    } catch {
        Add-AcceptanceResult -Id "object-storage-runtime-protocol" -Status "FAIL" `
                -Summary "Object storage protocol acceptance failed without recording credentials, endpoint details, or Maven output." `
                -Details ([ordered]@{ stage = $stage; errorType = $_.Exception.GetType().Name })
    } finally {
        foreach ($entry in $savedEnvironment.GetEnumerator()) {
            [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, [EnvironmentVariableTarget]::Process)
        }
        if ($null -ne $reportDirectory -and $null -ne $reportSuffix -and (Test-Path -LiteralPath $reportDirectory)) {
            $verifiedReportRoot = [IO.Path]::GetFullPath($reportDirectory)
            $expectedReportRoot = [IO.Path]::GetFullPath((Join-Path ([IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))) `
                    "studio-test\target\surefire-reports"))
            if ($verifiedReportRoot.Equals($expectedReportRoot, [StringComparison]::OrdinalIgnoreCase)) {
                Get-ChildItem -LiteralPath $verifiedReportRoot -File -Filter "*$reportSuffix*" -ErrorAction SilentlyContinue |
                        ForEach-Object { Remove-Item -LiteralPath $_.FullName -Force }
            }
        }
        if ($null -ne $temporaryRoot -and (Test-Path -LiteralPath $temporaryRoot)) {
            $temporaryBase = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
            $verifiedRoot = [IO.Path]::GetFullPath($temporaryRoot)
            if ($verifiedRoot.StartsWith($temporaryBase, [StringComparison]::OrdinalIgnoreCase) -and
                    [IO.Path]::GetFileName($verifiedRoot).StartsWith("studio-runtime-acceptance-", [StringComparison]::Ordinal)) {
                Remove-Item -LiteralPath $verifiedRoot -Recurse -Force
            }
        }
    }

    foreach ($manualCheck in @(
            @{ Id = "object-storage-network-acl"; StatusVariable = "STUDIO_ACCEPTANCE_OBJECT_NETWORK_ACL_STATUS"; EvidenceVariable = "STUDIO_ACCEPTANCE_OBJECT_NETWORK_ACL_EVIDENCE_ID"; Summary = "Production object storage network ACL review" },
            @{ Id = "object-storage-capacity"; StatusVariable = "STUDIO_ACCEPTANCE_OBJECT_CAPACITY_STATUS"; EvidenceVariable = "STUDIO_ACCEPTANCE_OBJECT_CAPACITY_EVIDENCE_ID"; Summary = "Production object storage capacity review" },
            @{ Id = "object-storage-lifecycle"; StatusVariable = "STUDIO_ACCEPTANCE_OBJECT_LIFECYCLE_STATUS"; EvidenceVariable = "STUDIO_ACCEPTANCE_OBJECT_LIFECYCLE_EVIDENCE_ID"; Summary = "Production object storage lifecycle review" },
            @{ Id = "object-storage-ha"; StatusVariable = "STUDIO_ACCEPTANCE_OBJECT_HA_STATUS"; EvidenceVariable = "STUDIO_ACCEPTANCE_OBJECT_HA_EVIDENCE_ID"; Summary = "Production object storage high-availability review" }
        )) {
        $attestation = Get-ManualAttestationStatus -StatusEnvironmentName $manualCheck.StatusVariable `
                -EvidenceEnvironmentName $manualCheck.EvidenceVariable
        Add-AcceptanceResult -Id $manualCheck.Id -Status $attestation.Status -Summary $manualCheck.Summary `
                -Details ([ordered]@{ evidence = $attestation.Reason; evidenceId = $attestation.EvidenceId })
    }
}

function Write-AcceptanceReport {
    $failedCount = @($results | Where-Object { $_.status -eq "FAIL" }).Count
    $pendingCount = @($results | Where-Object { $_.status -eq "PENDING" }).Count
    $overall = "PASS"
    if ($failedCount -gt 0) {
        $overall = "FAIL"
    } elseif ($pendingCount -gt 0) {
        $overall = "INCOMPLETE"
    }
    $safeNodeLabel = $NodeLabel.Replace("`r", " ").Replace("`n", " ").Replace("|", "/")
    $record = [ordered]@{
        schemaVersion = "studio.production-runtime.acceptance.v2"
        generatedAt = [DateTimeOffset]::UtcNow.ToString("o")
        nodeLabel = $safeNodeLabel
        nodeRole = $NodeRole
        runtimeClusterId = $script:reportRuntimeClusterId
        requestedChecks = @($Checks)
        networkExpectation = $NetworkExpectation.ToUpperInvariant()
        overallStatus = $overall
        secretValuesPersisted = $false
        results = @($results)
    }
    $json = $record | ConvertTo-Json -Depth 10
    Assert-NoSecretText -Text $json -Context "JSON acceptance report"

    if (-not (Has-Text -Value $ResultDirectory)) {
        $backendRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
        $ResultDirectory = Join-Path $backendRoot "target\studio-production-acceptance"
    }
    $resultRoot = [IO.Path]::GetFullPath($ResultDirectory)
    [void](New-Item -ItemType Directory -Path $resultRoot -Force)
    $suffix = [DateTimeOffset]::UtcNow.ToString("yyyyMMddTHHmmssZ") + "-" + [Guid]::NewGuid().ToString("N").Substring(0, 8)
    $jsonPath = Join-Path $resultRoot "studio-production-runtime-$suffix.json"
    $markdownPath = Join-Path $resultRoot "studio-production-runtime-$suffix.md"

    $markdownLines = [System.Collections.Generic.List[string]]::new()
    [void]$markdownLines.Add("# Studio production runtime acceptance")
    [void]$markdownLines.Add("")
    [void]$markdownLines.Add("- Generated at: ``$($record.generatedAt)``")
    [void]$markdownLines.Add("- Node label: ``$safeNodeLabel``")
    [void]$markdownLines.Add("- Node role: ``$NodeRole``")
    [void]$markdownLines.Add("- Runtime cluster ID: ``$(if ($null -eq $script:reportRuntimeClusterId) { 'not-applicable' } else { $script:reportRuntimeClusterId })``")
    [void]$markdownLines.Add("- Requested checks: ``$($Checks -join ', ')``")
    [void]$markdownLines.Add("- Overall status: **$overall**")
    [void]$markdownLines.Add("- Secrets persisted: **no**")
    [void]$markdownLines.Add("")
    [void]$markdownLines.Add("| Check | Status | Summary |")
    [void]$markdownLines.Add("| --- | --- | --- |")
    foreach ($result in $results) {
        $summary = ([string]$result.summary).Replace("|", "/").Replace("`r", " ").Replace("`n", " ")
        [void]$markdownLines.Add("| ``$($result.id)`` | $($result.status) | $summary |")
    }
    [void]$markdownLines.Add("")
    [void]$markdownLines.Add("JSON contains only redacted endpoint hashes, Header names, status codes, and non-secret evidence metadata.")
    $markdown = $markdownLines -join "`n"
    Assert-NoSecretText -Text $markdown -Context "Markdown acceptance report"

    $utf8 = [Text.UTF8Encoding]::new($false)
    [IO.File]::WriteAllText($jsonPath, $json + "`n", $utf8)
    [IO.File]::WriteAllText($markdownPath, $markdown + "`n", $utf8)
    Write-Host "Studio production runtime acceptance status: $overall"
    Write-Host "Redacted JSON report: $jsonPath"
    Write-Host "Redacted Markdown report: $markdownPath"
    return $overall
}

$selectedChecks = @($Checks | Select-Object -Unique)
if ($selectedChecks -contains "Network") {
    if ($NetworkExpectation -eq "Denied" -and $NodeRole -ne "OMS") {
        throw "NetworkExpectation Denied must run with -NodeRole OMS."
    }
    if ($NetworkExpectation -eq "Allowed" -and $NodeRole -ne "WORKER") {
        throw "NetworkExpectation Allowed must run with -NodeRole WORKER."
    }
}
if (($selectedChecks -contains "ObjectStorage") -and $NodeRole -notin @("WORKER", "RELEASE_RUNNER")) {
    throw "ObjectStorage acceptance must run with -NodeRole WORKER or RELEASE_RUNNER."
}
if (($selectedChecks -contains "RuntimeEndpoint") -or
        (($selectedChecks -contains "Network") -and $NodeRole -eq "WORKER") -or
        (($selectedChecks -contains "ObjectStorage") -and $NodeRole -eq "WORKER")) {
    $reportClusterId = Require-EnvironmentValue -Name "STUDIO_ACCEPTANCE_RUNTIME_CLUSTER_ID"
    if ($reportClusterId -notmatch '^\d+$') {
        throw "STUDIO_ACCEPTANCE_RUNTIME_CLUSTER_ID must be a numeric database ID."
    }
    $script:reportRuntimeClusterId = $reportClusterId
}
if ($selectedChecks -contains "RuntimeEndpoint") {
    Invoke-RuntimeEndpointAcceptance
}
if ($selectedChecks -contains "Network") {
    Invoke-NetworkAcceptance
}
if ($selectedChecks -contains "ObjectStorage") {
    Invoke-ObjectStorageAcceptance
}

$status = Write-AcceptanceReport
if ($status -eq "FAIL") {
    throw "Studio production runtime acceptance failed. Review the redacted report."
}
if ($status -eq "INCOMPLETE" -and $RequireComplete) {
    throw "Studio production runtime acceptance still requires manual evidence. Review the redacted report."
}
