[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateCount(1, 200)]
    [string[]]$ReportPaths,
    [Parameter(Mandatory = $true)]
    [ValidateCount(1, 100)]
    [string[]]$RuntimeClusterIds,
    [ValidateSet("OSS", "MINIO")]
    [string]$ExpectedObjectProvider = "OSS",
    [ValidateRange(1, 720)]
    [int]$MaxReportAgeHours = 72,
    [ValidateSet("PASS", "FAIL", "PENDING")]
    [string]$RollbackStatus = "PENDING",
    [string]$RollbackEvidenceId,
    [string]$RollbackCompletedAt,
    [string]$ResultDirectory
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$summaryResults = [System.Collections.Generic.List[object]]::new()
$sourceSummaries = [System.Collections.Generic.List[object]]::new()
$validReports = [System.Collections.Generic.List[object]]::new()
$seenReportHashes = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
$sourceIndex = 0

function Has-Text {
    param([AllowNull()][string]$Value)
    return $null -ne $Value -and -not [string]::IsNullOrWhiteSpace($Value)
}

function Add-SummaryResult {
    param(
        [Parameter(Mandatory = $true)][string]$Id,
        [Parameter(Mandatory = $true)][ValidateSet("PASS", "FAIL")][string]$Status,
        [Parameter(Mandatory = $true)][string]$Summary,
        [hashtable]$Details = @{}
    )
    [void]$summaryResults.Add([ordered]@{
        id = $Id
        status = $Status
        summary = $Summary
        details = $Details
    })
}

function Get-Sha256Hex {
    param([Parameter(Mandatory = $true)][byte[]]$Bytes)
    $sha256 = [Security.Cryptography.SHA256]::Create()
    try {
        return [BitConverter]::ToString($sha256.ComputeHash($Bytes)).Replace("-", "").ToLowerInvariant()
    } finally {
        $sha256.Dispose()
    }
}

function Get-ReportResult {
    param(
        [Parameter(Mandatory = $true)][object]$Report,
        [Parameter(Mandatory = $true)][string]$Id
    )
    return @($Report.results | Where-Object { [string]$_.id -eq $Id }) | Select-Object -First 1
}

function Test-RequestedCheck {
    param(
        [Parameter(Mandatory = $true)][object]$Report,
        [Parameter(Mandatory = $true)][string]$Check
    )
    return @($Report.requestedChecks | Where-Object { [string]$_ -eq $Check }).Count -gt 0
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

function Test-PositiveNumericId {
    param([AllowNull()][string]$Value)
    return (Has-Text -Value $Value) -and $Value -match '^[1-9][0-9]*$'
}

function Test-RedactedEndpoint {
    param(
        [AllowNull()][string]$Value,
        [switch]$RequireHttps
    )
    if (-not (Has-Text -Value $Value)) {
        return $false
    }
    $schemePattern = $(if ($RequireHttps) { 'https' } else { 'https?' })
    $match = [regex]::Match($Value, ("^" + $schemePattern + '://host-sha256:[0-9a-f]{12}(?::([1-9][0-9]{0,4}))?/[^?#\s]*$'))
    if (-not $match.Success) {
        return $false
    }
    if ($match.Groups[1].Success) {
        $port = 0
        return [int]::TryParse($match.Groups[1].Value, [ref]$port) -and $port -le 65535
    }
    return $true
}

function Test-RedactedNetworkTarget {
    param([AllowNull()][string]$Value)
    if (-not (Has-Text -Value $Value)) {
        return $false
    }
    $match = [regex]::Match($Value, '^host-sha256:[0-9a-f]{12}:([1-9][0-9]{0,4})$')
    if (-not $match.Success) {
        return $false
    }
    $port = 0
    return [int]::TryParse($match.Groups[1].Value, [ref]$port) -and $port -le 65535
}

function Test-TransportHeaderNames {
    param([AllowNull()][object]$Value)
    if ($null -eq $Value -or $Value -isnot [array]) {
        return $false
    }
    $names = @($Value)
    if ($names.Count -eq 0 -or $names.Count -gt 32) {
        return $false
    }
    $blocked = @(
        "host", "content-length", "connection", "transfer-encoding", "keep-alive",
        "te", "trailer", "upgrade", "x-studio-internal-token", "x-studio-target-cluster-id"
    )
    $seen = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach ($nameValue in $names) {
        $name = [string]$nameValue
        if ($name -notmatch '^[A-Za-z0-9!#$%&''*+.^_`|~-]{1,128}$' -or
                $blocked -contains $name.ToLowerInvariant() -or -not $seen.Add($name)) {
            return $false
        }
    }
    return $true
}

function Assert-IntegerProperty {
    param(
        [Parameter(Mandatory = $true)][object]$Object,
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][int]$Expected
    )
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property -or $property.Value -isnot [int] -or [int]$property.Value -ne $Expected) {
        throw "Acceptance result contains an invalid integer field."
    }
}

function Assert-BooleanProperty {
    param(
        [Parameter(Mandatory = $true)][object]$Object,
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][bool]$Expected
    )
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property -or $property.Value -isnot [bool] -or [bool]$property.Value -ne $Expected) {
        throw "Acceptance result contains an invalid Boolean field."
    }
}

function Get-NonNegativeNumberProperty {
    param(
        [Parameter(Mandatory = $true)][object]$Object,
        [Parameter(Mandatory = $true)][string]$Name
    )
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property -or $property.Value -is [bool]) {
        throw "Acceptance result contains an invalid numeric field."
    }
    $number = 0.0
    try {
        $number = [Convert]::ToDouble($property.Value, [Globalization.CultureInfo]::InvariantCulture)
    } catch {
        throw "Acceptance result contains an invalid numeric field."
    }
    if ([double]::IsNaN($number) -or [double]::IsInfinity($number) -or $number -lt 0) {
        throw "Acceptance result contains an invalid numeric field."
    }
    return $number
}

function Assert-ResultSchema {
    param([Parameter(Mandatory = $true)][object]$Result)
    $details = $Result.PSObject.Properties["details"].Value
    switch ([string]$Result.id) {
        "runtime-endpoint-protocol" {
            if (-not (Test-RedactedEndpoint -Value ([string]$details.endpoint) -RequireHttps) -or
                    -not (Test-PositiveNumericId -Value ([string]$details.targetClusterId)) -or
                    -not (Test-TransportHeaderNames -Value $details.transportHeaderNames) -or
                    [string]$details.secretLeakScan -ne "PASS") {
                throw "Runtime endpoint evidence contains invalid or non-redacted fields."
            }
            Assert-IntegerProperty -Object $details -Name "rejectedStatus" -Expected 401
            Assert-IntegerProperty -Object $details -Name "responseBodyLimitBytes" -Expected 16384
            Assert-IntegerProperty -Object $details -Name "performanceErrors" -Expected 0
            Assert-BooleanProperty -Object $details -Name "redirectsAllowed" -Expected $false
            $authenticatedStatus = $details.PSObject.Properties["authenticatedStatus"]
            if ($null -eq $authenticatedStatus -or $authenticatedStatus.Value -isnot [int] -or
                    [int]$authenticatedStatus.Value -lt 200 -or [int]$authenticatedStatus.Value -ge 300) {
                throw "Runtime endpoint evidence contains an invalid authenticated status."
            }
            $sampleCount = $details.PSObject.Properties["performanceSampleCount"]
            $maxP95 = $details.PSObject.Properties["performanceMaxP95Ms"]
            if ($null -eq $sampleCount -or $sampleCount.Value -isnot [int] -or
                    [int]$sampleCount.Value -lt 5 -or [int]$sampleCount.Value -gt 200 -or
                    $null -eq $maxP95 -or $maxP95.Value -isnot [int] -or
                    [int]$maxP95.Value -lt 1 -or [int]$maxP95.Value -gt 60000) {
                throw "Runtime endpoint evidence contains an invalid performance configuration."
            }
            $p50 = Get-NonNegativeNumberProperty -Object $details -Name "performanceP50Ms"
            $p95 = Get-NonNegativeNumberProperty -Object $details -Name "performanceP95Ms"
            $p99 = Get-NonNegativeNumberProperty -Object $details -Name "performanceP99Ms"
            $maximum = Get-NonNegativeNumberProperty -Object $details -Name "performanceMaxMs"
            if ($p50 -gt $p95 -or $p95 -gt $p99 -or $p99 -gt $maximum -or
                    $p95 -gt [int]$maxP95.Value) {
                throw "Runtime endpoint evidence does not satisfy its latency baseline."
            }
        }
        "datasource-network-boundary" {
            if (-not (Test-RedactedNetworkTarget -Value ([string]$details.target)) -or
                    [string]$details.expectation -notin @("ALLOWED", "DENIED") -or
                    [string]$details.observed -notin @("REACHABLE", "UNREACHABLE") -or
                    (([string]$details.expectation -eq "ALLOWED") -ne ([string]$details.observed -eq "REACHABLE"))) {
                throw "Network evidence contains invalid or non-redacted fields."
            }
            if ($null -ne $details.runtimeClusterId -and
                    -not (Test-PositiveNumericId -Value ([string]$details.runtimeClusterId))) {
                throw "Network evidence contains an invalid runtime cluster ID."
            }
        }
        "object-storage-runtime-protocol" {
            if ([string]$details.provider -notin @("OSS", "MINIO") -or
                    -not (Test-RedactedEndpoint -Value ([string]$details.endpoint)) -or
                    [string]$details.bucket -notmatch '^bucket-sha256:[0-9a-f]{12}$') {
                throw "Object storage evidence contains invalid or non-redacted fields."
            }
            Assert-BooleanProperty -Object $details -Name "createBucket" -Expected $false
            Assert-BooleanProperty -Object $details -Name "temporaryObjectsCleanedByTest" -Expected $true
            Assert-IntegerProperty -Object $details -Name "tests" -Expected 1
            Assert-IntegerProperty -Object $details -Name "failures" -Expected 0
            Assert-IntegerProperty -Object $details -Name "errors" -Expected 0
            Assert-IntegerProperty -Object $details -Name "skipped" -Expected 0
        }
        { $_ -in @("object-storage-network-acl", "object-storage-capacity", "object-storage-lifecycle", "object-storage-ha") } {
            if ([string]$details.evidence -ne "OPERATOR_ATTESTED" -or
                    -not (Test-EvidenceId -Value ([string]$details.evidenceId))) {
                throw "Object storage attestation is missing a traceable evidence ID."
            }
        }
        default {
            throw "Acceptance report contains an unknown result type."
        }
    }
}

function Assert-SourceReportConsistency {
    param([Parameter(Mandatory = $true)][object]$Report)
    $allowedChecks = @("RuntimeEndpoint", "Network", "ObjectStorage")
    if ($Report.requestedChecks -isnot [array]) {
        throw "Acceptance report requested checks must be a JSON array."
    }
    $checks = @($Report.requestedChecks)
    if ($checks.Count -eq 0) {
        throw "Acceptance report must contain at least one requested check."
    }
    $checkSet = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($checkValue in $checks) {
        $check = [string]$checkValue
        if ($check -notin $allowedChecks -or -not $checkSet.Add($check)) {
            throw "Acceptance report contains an unknown or duplicated requested check."
        }
    }

    if ($null -ne $Report.runtimeClusterId -and
            -not (Test-PositiveNumericId -Value ([string]$Report.runtimeClusterId))) {
        throw "Acceptance report runtime cluster ID is invalid."
    }
    $requiresClusterId = $checkSet.Contains("RuntimeEndpoint") -or
            ($checkSet.Contains("Network") -and [string]$Report.nodeRole -eq "WORKER") -or
            ($checkSet.Contains("ObjectStorage") -and [string]$Report.nodeRole -eq "WORKER")
    if ($requiresClusterId -and -not (Test-PositiveNumericId -Value ([string]$Report.runtimeClusterId))) {
        throw "Acceptance report is missing its required runtime cluster ID."
    }

    $networkExpectation = [string]$Report.networkExpectation
    if ($checkSet.Contains("Network")) {
        if (([string]$Report.nodeRole -eq "OMS" -and $networkExpectation -ne "DENIED") -or
                ([string]$Report.nodeRole -eq "WORKER" -and $networkExpectation -ne "ALLOWED") -or
                [string]$Report.nodeRole -eq "RELEASE_RUNNER") {
            throw "Acceptance report network expectation does not match its node role."
        }
    } elseif ($networkExpectation -ne "NONE") {
        throw "Acceptance report declares a network expectation without a network check."
    }

    if ($Report.results -isnot [array]) {
        throw "Acceptance report results must be a JSON array."
    }
    $expectedResultIds = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    if ($checkSet.Contains("RuntimeEndpoint")) {
        [void]$expectedResultIds.Add("runtime-endpoint-protocol")
    }
    if ($checkSet.Contains("Network")) {
        [void]$expectedResultIds.Add("datasource-network-boundary")
    }
    if ($checkSet.Contains("ObjectStorage")) {
        foreach ($resultId in @(
                "object-storage-runtime-protocol", "object-storage-network-acl", "object-storage-capacity",
                "object-storage-lifecycle", "object-storage-ha")) {
            [void]$expectedResultIds.Add($resultId)
        }
    }
    $results = @($Report.results)
    if ($results.Count -ne $expectedResultIds.Count) {
        throw "Acceptance report result count does not match the requested checks."
    }
    $seenResultIds = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($result in $results) {
        if ($null -eq $result -or $null -eq $result.PSObject.Properties["id"] -or
                $null -eq $result.PSObject.Properties["status"] -or $null -eq $result.PSObject.Properties["details"]) {
            throw "Acceptance report contains an incomplete result."
        }
        $resultId = [string]$result.id
        if (-not $expectedResultIds.Contains($resultId) -or -not $seenResultIds.Add($resultId) -or
                [string]$result.status -ne "PASS" -or $null -eq $result.details) {
            throw "Acceptance report contains an unknown, duplicated, failed, or incomplete result."
        }
        Assert-ResultSchema -Result $result
    }
}

function Get-SafeValidationCode {
    param([Parameter(Mandatory = $true)][string]$Message)
    $codes = @{
        "Acceptance report requested checks must be a JSON array." = "REQUESTED_CHECKS_NOT_ARRAY"
        "Acceptance report must contain at least one requested check." = "REQUESTED_CHECKS_EMPTY"
        "Acceptance report contains an unknown or duplicated requested check." = "REQUESTED_CHECKS_INVALID"
        "Acceptance report runtime cluster ID is invalid." = "RUNTIME_CLUSTER_ID_INVALID"
        "Acceptance report is missing its required runtime cluster ID." = "RUNTIME_CLUSTER_ID_MISSING"
        "Acceptance report network expectation does not match its node role." = "NETWORK_ROLE_MISMATCH"
        "Acceptance report declares a network expectation without a network check." = "NETWORK_EXPECTATION_UNEXPECTED"
        "Acceptance report results must be a JSON array." = "RESULTS_NOT_ARRAY"
        "Acceptance report result count does not match the requested checks." = "RESULT_COUNT_MISMATCH"
        "Acceptance report contains an incomplete result." = "RESULT_INCOMPLETE"
        "Acceptance report contains an unknown, duplicated, failed, or incomplete result." = "RESULT_INVALID"
        "Runtime endpoint evidence contains invalid or non-redacted fields." = "RUNTIME_EVIDENCE_INVALID"
        "Runtime endpoint evidence contains an invalid authenticated status." = "RUNTIME_STATUS_INVALID"
        "Network evidence contains invalid or non-redacted fields." = "NETWORK_EVIDENCE_INVALID"
        "Network evidence contains an invalid runtime cluster ID." = "NETWORK_CLUSTER_ID_INVALID"
        "Object storage evidence contains invalid or non-redacted fields." = "OBJECT_EVIDENCE_INVALID"
        "Object storage attestation is missing a traceable evidence ID." = "OBJECT_ATTESTATION_INVALID"
        "Acceptance report contains an unknown result type." = "RESULT_TYPE_UNKNOWN"
        "Acceptance result contains an invalid integer field." = "RESULT_INTEGER_INVALID"
        "Acceptance result contains an invalid Boolean field." = "RESULT_BOOLEAN_INVALID"
        "Acceptance result contains an invalid numeric field." = "RESULT_NUMBER_INVALID"
        "Runtime endpoint evidence contains an invalid performance configuration." = "RUNTIME_PERFORMANCE_CONFIG_INVALID"
        "Runtime endpoint evidence does not satisfy its latency baseline." = "RUNTIME_PERFORMANCE_BASELINE_FAILED"
    }
    if ($codes.ContainsKey($Message)) {
        return $codes[$Message]
    }
    return "VALIDATION_FAILED"
}

$expectedClusters = @($RuntimeClusterIds | ForEach-Object {
    $value = ([string]$_).Trim()
    if (-not (Test-PositiveNumericId -Value $value)) {
        throw "RuntimeClusterIds must contain positive numeric database IDs only."
    }
    $value
} | Select-Object -Unique)
if ($expectedClusters.Count -eq 0) {
    throw "At least one runtime cluster ID is required."
}

$now = [DateTimeOffset]::UtcNow
foreach ($reportPath in $ReportPaths) {
    $sourceIndex++
    $sourceValid = $true
    $sourceStage = "PATH"
    $report = $null
    $reportHash = $null
    try {
        $resolvedPath = [IO.Path]::GetFullPath($reportPath)
        if (-not (Test-Path -LiteralPath $resolvedPath -PathType Leaf)) {
            throw "Acceptance report file does not exist."
        }
        $reportFile = Get-Item -LiteralPath $resolvedPath
        $sourceStage = "SIZE"
        if ($reportFile.Length -le 0 -or $reportFile.Length -gt 1048576) {
            throw "Acceptance report size is outside the allowed 1 MiB limit."
        }
        $bytes = [IO.File]::ReadAllBytes($resolvedPath)
        $reportHash = Get-Sha256Hex -Bytes $bytes
        if (-not $seenReportHashes.Add($reportHash)) {
            throw "The same acceptance report was supplied more than once."
        }
        $sourceStage = "JSON"
        $report = ([Text.Encoding]::UTF8.GetString($bytes)) | ConvertFrom-Json
        $sourceStage = "SCHEMA"
        if ([string]$report.schemaVersion -ne "studio.production-runtime.acceptance.v2") {
            throw "Acceptance report schema is not supported."
        }
        if ([string]$report.overallStatus -ne "PASS" -or
                $report.secretValuesPersisted -isnot [bool] -or [bool]$report.secretValuesPersisted) {
            throw "Only complete redacted PASS reports can be summarized."
        }
        if ([string]$report.nodeRole -notin @("OMS", "WORKER", "RELEASE_RUNNER")) {
            throw "Acceptance report node role is invalid."
        }
        $sourceStage = "CONSISTENCY"
        Assert-SourceReportConsistency -Report $report
        $sourceStage = "TIMESTAMP"
        $generatedAt = [DateTimeOffset]::MinValue
        if (-not [DateTimeOffset]::TryParse([string]$report.generatedAt, [ref]$generatedAt)) {
            throw "Acceptance report timestamp is invalid."
        }
        if ($generatedAt -gt $now.AddMinutes(5) -or $generatedAt -lt $now.AddHours(-$MaxReportAgeHours)) {
            throw "Acceptance report is outside the allowed evidence age window."
        }
        [void]$sourceSummaries.Add([ordered]@{
            reportSha256 = $reportHash
            generatedAt = $generatedAt.ToString("o")
            nodeRole = [string]$report.nodeRole
            runtimeClusterId = $(if ($null -eq $report.runtimeClusterId) { $null } else { [string]$report.runtimeClusterId })
            requestedChecks = @($report.requestedChecks)
        })
        [void]$validReports.Add($report)
    } catch {
        $sourceValid = $false
        $validationCode = Get-SafeValidationCode -Message ([string]$_.Exception.Message)
        Add-SummaryResult -Id "source-report-$sourceIndex" -Status "FAIL" `
                -Summary "A source acceptance report was missing, duplicated, stale, incomplete, or invalid." `
                -Details ([ordered]@{
                    validationStage = $sourceStage
                    validationCode = $validationCode
                    errorType = $_.Exception.GetType().Name
                    reportHashKnown = $null -ne $reportHash
                })
    }
    if ($sourceValid) {
        Add-SummaryResult -Id "source-report-$sourceIndex" -Status "PASS" `
                -Summary "A fresh redacted production acceptance report passed schema and integrity checks." `
                -Details ([ordered]@{ reportSha256 = $reportHash })
    }
}

foreach ($clusterId in $expectedClusters) {
    $runtimeMatch = @($validReports | Where-Object {
        (Test-RequestedCheck -Report $_ -Check "RuntimeEndpoint") -and
        [string]$_.runtimeClusterId -eq $clusterId -and
        $null -ne (Get-ReportResult -Report $_ -Id "runtime-endpoint-protocol")
    } | Where-Object {
        $result = Get-ReportResult -Report $_ -Id "runtime-endpoint-protocol"
        [string]$result.status -eq "PASS" -and
        [string]$result.details.targetClusterId -eq $clusterId -and
        [string]$result.details.secretLeakScan -eq "PASS" -and
        [int]$result.details.authenticatedStatus -ge 200 -and
        [int]$result.details.authenticatedStatus -lt 300 -and
        [int]$result.details.rejectedStatus -eq 401 -and
        $result.details.redirectsAllowed -is [bool] -and -not [bool]$result.details.redirectsAllowed -and
        [string]$result.details.endpoint -like "https://*" -and
        [int]$result.details.responseBodyLimitBytes -eq 16384 -and
        [int]$result.details.performanceSampleCount -ge 5 -and
        [int]$result.details.performanceErrors -eq 0 -and
        [double]$result.details.performanceP95Ms -le [int]$result.details.performanceMaxP95Ms -and
        (Test-TransportHeaderNames -Value $result.details.transportHeaderNames)
    }).Count -gt 0
    Add-SummaryResult -Id "runtime-cluster-$clusterId" -Status $(if ($runtimeMatch) { "PASS" } else { "FAIL" }) `
            -Summary $(if ($runtimeMatch) {
                "The runtime cluster has a fresh HTTPS SLB authentication and secret-isolation report."
            } else {
                "The runtime cluster is missing a fresh HTTPS SLB authentication and secret-isolation report."
            }) -Details ([ordered]@{ runtimeClusterId = $clusterId })
}

foreach ($clusterId in $expectedClusters) {
    $workerReports = @($validReports | Where-Object {
        (Test-RequestedCheck -Report $_ -Check "Network") -and
        [string]$_.nodeRole -eq "WORKER" -and
        [string]$_.runtimeClusterId -eq $clusterId -and
        [string]$_.networkExpectation -eq "ALLOWED"
    })
    $matchedBoundary = $false
    foreach ($workerReport in $workerReports) {
        $workerResult = Get-ReportResult -Report $workerReport -Id "datasource-network-boundary"
        if ($null -eq $workerResult -or [string]$workerResult.status -ne "PASS" -or
                [string]$workerResult.details.observed -ne "REACHABLE" -or
                [string]$workerResult.details.expectation -ne "ALLOWED" -or
                [string]$workerResult.details.runtimeClusterId -ne $clusterId -or
                -not (Test-RedactedNetworkTarget -Value ([string]$workerResult.details.target))) {
            continue
        }
        $targetHash = [string]$workerResult.details.target
        $deniedMatch = @($validReports | Where-Object {
            (Test-RequestedCheck -Report $_ -Check "Network") -and
            [string]$_.nodeRole -eq "OMS" -and
            [string]$_.networkExpectation -eq "DENIED"
        } | Where-Object {
            $omsResult = Get-ReportResult -Report $_ -Id "datasource-network-boundary"
            $null -ne $omsResult -and [string]$omsResult.status -eq "PASS" -and
            [string]$omsResult.details.observed -eq "UNREACHABLE" -and
            [string]$omsResult.details.expectation -eq "DENIED" -and
            [string]$omsResult.details.target -eq $targetHash
        }).Count -gt 0
        if ($deniedMatch) {
            $matchedBoundary = $true
            break
        }
    }
    Add-SummaryResult -Id "network-boundary-$clusterId" -Status $(if ($matchedBoundary) { "PASS" } else { "FAIL" }) `
            -Summary $(if ($matchedBoundary) {
                "The Worker allowed report is paired with an OMS denied report for the same redacted data source target."
            } else {
                "The cluster is missing a paired Worker-allowed and OMS-denied report for the same data source target."
            }) -Details ([ordered]@{ runtimeClusterId = $clusterId })
}

$requiredObjectResults = @(
    "object-storage-runtime-protocol",
    "object-storage-network-acl",
    "object-storage-capacity",
    "object-storage-lifecycle",
    "object-storage-ha"
)
$completeObjectReport = $false
foreach ($report in @($validReports | Where-Object {
        (Test-RequestedCheck -Report $_ -Check "ObjectStorage") -and
        [string]$_.nodeRole -in @("WORKER", "RELEASE_RUNNER")
    })) {
    $protocolResult = Get-ReportResult -Report $report -Id "object-storage-runtime-protocol"
    if ($null -eq $protocolResult -or [string]$protocolResult.status -ne "PASS" -or
            [string]$protocolResult.details.provider -ne $ExpectedObjectProvider -or
            $protocolResult.details.createBucket -isnot [bool] -or [bool]$protocolResult.details.createBucket -or
            [int]$protocolResult.details.tests -ne 1 -or [int]$protocolResult.details.failures -ne 0 -or
            [int]$protocolResult.details.errors -ne 0 -or [int]$protocolResult.details.skipped -ne 0 -or
            $protocolResult.details.temporaryObjectsCleanedByTest -isnot [bool] -or
            -not [bool]$protocolResult.details.temporaryObjectsCleanedByTest -or
            ($ExpectedObjectProvider -eq "OSS" -and [string]$protocolResult.details.endpoint -notlike "https://*")) {
        continue
    }
    $allComplete = $true
    foreach ($resultId in $requiredObjectResults) {
        $result = Get-ReportResult -Report $report -Id $resultId
        if ($null -eq $result -or [string]$result.status -ne "PASS") {
            $allComplete = $false
            break
        }
        if ($resultId -ne "object-storage-runtime-protocol" -and
                ([string]$result.details.evidence -ne "OPERATOR_ATTESTED" -or
                -not (Test-EvidenceId -Value ([string]$result.details.evidenceId)))) {
            $allComplete = $false
            break
        }
    }
    if ($allComplete) {
        $completeObjectReport = $true
        break
    }
}
Add-SummaryResult -Id "object-storage-production" -Status $(if ($completeObjectReport) { "PASS" } else { "FAIL" }) `
        -Summary $(if ($completeObjectReport) {
            "The production object storage protocol and all four traceable operational attestations are complete."
        } else {
            "A complete production object storage report with protocol proof and four evidence IDs is missing."
        }) -Details ([ordered]@{ expectedProvider = $ExpectedObjectProvider; createBucketRequired = $false })

$rollbackTimestamp = [DateTimeOffset]::MinValue
$rollbackTimestampValid = (Has-Text -Value $RollbackCompletedAt) -and
        [DateTimeOffset]::TryParse($RollbackCompletedAt, [ref]$rollbackTimestamp)
$rollbackFresh = $rollbackTimestampValid -and
        $rollbackTimestamp -le $now.AddMinutes(5) -and
        $rollbackTimestamp -ge $now.AddHours(-$MaxReportAgeHours)
$rollbackEvidenceValid = Test-EvidenceId -Value $RollbackEvidenceId
$rollbackComplete = $RollbackStatus -eq "PASS" -and $rollbackEvidenceValid -and $rollbackFresh
Add-SummaryResult -Id "production-rollback" -Status $(if ($rollbackComplete) { "PASS" } else { "FAIL" }) `
        -Summary $(if ($rollbackComplete) {
            "A fresh production-equivalent application rollback drill has a traceable PASS evidence record."
        } else {
            "A fresh production-equivalent application rollback PASS with a traceable evidence ID is missing."
        }) -Details ([ordered]@{
            evidence = $(if ($rollbackEvidenceValid) { "OPERATOR_ATTESTED" } else { $null })
            evidenceId = $(if ($rollbackEvidenceValid) { $RollbackEvidenceId.Trim() } else { $null })
            completedAt = $(if ($rollbackTimestampValid) { $rollbackTimestamp.ToUniversalTime().ToString("o") } else { $null })
            maxEvidenceAgeHours = $MaxReportAgeHours
        })

$failedCount = @($summaryResults | Where-Object { $_.status -eq "FAIL" }).Count
$overallStatus = $(if ($failedCount -eq 0) { "PASS" } else { "FAIL" })
$summaryRecord = [ordered]@{
    schemaVersion = "studio.production-runtime.acceptance-summary.v2"
    generatedAt = $now.ToString("o")
    overallStatus = $overallStatus
    expectedRuntimeClusterIds = $expectedClusters
    expectedObjectProvider = $ExpectedObjectProvider
    maxReportAgeHours = $MaxReportAgeHours
    sourceReports = @($sourceSummaries)
    results = @($summaryResults)
}

if (-not (Has-Text -Value $ResultDirectory)) {
    $backendRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
    $ResultDirectory = Join-Path $backendRoot "target\studio-production-acceptance-summary"
}
$resultRoot = [IO.Path]::GetFullPath($ResultDirectory)
[void](New-Item -ItemType Directory -Path $resultRoot -Force)
$suffix = [DateTimeOffset]::UtcNow.ToString("yyyyMMddTHHmmssZ") + "-" + [Guid]::NewGuid().ToString("N").Substring(0, 8)
$jsonPath = Join-Path $resultRoot "studio-production-acceptance-summary-$suffix.json"
$markdownPath = Join-Path $resultRoot "studio-production-acceptance-summary-$suffix.md"
$json = $summaryRecord | ConvertTo-Json -Depth 10

$markdownLines = [System.Collections.Generic.List[string]]::new()
[void]$markdownLines.Add("# Studio production acceptance summary")
[void]$markdownLines.Add("")
[void]$markdownLines.Add("- Generated at: ``$($summaryRecord.generatedAt)``")
[void]$markdownLines.Add("- Overall status: **$overallStatus**")
[void]$markdownLines.Add("- Runtime clusters: ``$($expectedClusters -join ', ')``")
[void]$markdownLines.Add("- Object provider: ``$ExpectedObjectProvider``")
[void]$markdownLines.Add("- Production rollback evidence: required")
[void]$markdownLines.Add("- Source reports: ``$($sourceSummaries.Count)``")
[void]$markdownLines.Add("")
[void]$markdownLines.Add("| Check | Status | Summary |")
[void]$markdownLines.Add("| --- | --- | --- |")
foreach ($result in $summaryResults) {
    $safeSummary = ([string]$result.summary).Replace("|", "/").Replace("`r", " ").Replace("`n", " ")
    [void]$markdownLines.Add("| ``$($result.id)`` | $($result.status) | $safeSummary |")
}
[void]$markdownLines.Add("")
[void]$markdownLines.Add("Source paths, production hosts, credentials, response bodies, and Header values are not persisted.")
$markdown = $markdownLines -join "`n"
$utf8 = [Text.UTF8Encoding]::new($false)
[IO.File]::WriteAllText($jsonPath, $json + "`n", $utf8)
[IO.File]::WriteAllText($markdownPath, $markdown + "`n", $utf8)

Write-Host "Studio production acceptance summary status: $overallStatus"
Write-Host "Redacted JSON summary: $jsonPath"
Write-Host "Redacted Markdown summary: $markdownPath"
if ($overallStatus -ne "PASS") {
    throw "Studio production acceptance evidence is incomplete or invalid. Review the redacted summary."
}
