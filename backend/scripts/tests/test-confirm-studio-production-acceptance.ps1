$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$summaryScript = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\confirm-studio-production-acceptance.ps1"))
$temporaryBase = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$temporaryRoot = [IO.Path]::GetFullPath((Join-Path $temporaryBase ("studio-production-summary-test-" + [Guid]::NewGuid().ToString("N"))))

function Assert-True {
    param(
        [Parameter(Mandatory = $true)][bool]$Condition,
        [Parameter(Mandatory = $true)][string]$Message
    )
    if (-not $Condition) {
        throw $Message
    }
}

function New-AcceptanceResult {
    param(
        [Parameter(Mandatory = $true)][string]$Id,
        [Parameter(Mandatory = $true)][hashtable]$Details
    )
    return [ordered]@{ id = $Id; status = "PASS"; summary = "fixture"; details = $Details }
}

function Write-SourceReport {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][ValidateSet("OMS", "WORKER", "RELEASE_RUNNER")][string]$NodeRole,
        [AllowNull()][string]$RuntimeClusterId,
        [Parameter(Mandatory = $true)][string[]]$Checks,
        [string]$NetworkExpectation = "NONE",
        [Parameter(Mandatory = $true)][object[]]$Results,
        [DateTimeOffset]$GeneratedAt = [DateTimeOffset]::UtcNow,
        [string]$OverallStatus = "PASS"
    )
    $path = Join-Path $temporaryRoot ($Name + ".json")
    $record = [ordered]@{
        schemaVersion = "studio.production-runtime.acceptance.v2"
        generatedAt = $GeneratedAt.ToString("o")
        nodeLabel = $Name
        nodeRole = $NodeRole
        runtimeClusterId = $(if ([string]::IsNullOrEmpty($RuntimeClusterId)) { $null } else { $RuntimeClusterId })
        requestedChecks = $Checks
        networkExpectation = $NetworkExpectation
        overallStatus = $OverallStatus
        secretValuesPersisted = $false
        results = $Results
    }
    [IO.File]::WriteAllText($path, ($record | ConvertTo-Json -Depth 10), [Text.UTF8Encoding]::new($false))
    return $path
}

function Write-ReportFixture {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][object]$Record
    )
    $path = Join-Path $temporaryRoot ($Name + ".json")
    [IO.File]::WriteAllText($path, ($Record | ConvertTo-Json -Depth 10), [Text.UTF8Encoding]::new($false))
    return $path
}

function New-RuntimeResult {
    param([Parameter(Mandatory = $true)][string]$ClusterId)
    $hostHash = $(if ($ClusterId -eq "46") { "111111111111" } else { "222222222222" })
    return New-AcceptanceResult -Id "runtime-endpoint-protocol" -Details ([ordered]@{
        endpoint = "https://host-sha256:$hostHash/internal/runtime/health"
        targetClusterId = $ClusterId
        transportHeaderNames = @("X-SLB-Access-Token")
        authenticatedStatus = 200
        rejectedStatus = 401
        redirectsAllowed = $false
        responseBodyLimitBytes = 16384
        secretLeakScan = "PASS"
        performanceSampleCount = 20
        performanceP50Ms = 12.5
        performanceP95Ms = 20.0
        performanceP99Ms = 24.0
        performanceMaxMs = 25.0
        performanceMaxP95Ms = 1000
        performanceErrors = 0
    })
}

function New-NetworkResult {
    param(
        [Parameter(Mandatory = $true)][string]$Target,
        [Parameter(Mandatory = $true)][ValidateSet("REACHABLE", "UNREACHABLE")][string]$Observed,
        [AllowNull()][string]$RuntimeClusterId
    )
    return New-AcceptanceResult -Id "datasource-network-boundary" -Details ([ordered]@{
        target = $Target
        runtimeClusterId = $(if ([string]::IsNullOrEmpty($RuntimeClusterId)) { $null } else { $RuntimeClusterId })
        expectation = $(if ($Observed -eq "REACHABLE") { "ALLOWED" } else { "DENIED" })
        observed = $Observed
    })
}

function New-ObjectResults {
    $results = [System.Collections.Generic.List[object]]::new()
    [void]$results.Add((New-AcceptanceResult -Id "object-storage-runtime-protocol" -Details ([ordered]@{
        provider = "OSS"
        endpoint = "https://host-sha256:333333333333/"
        bucket = "bucket-sha256:444444444444"
        createBucket = $false
        tests = 1
        failures = 0
        errors = 0
        skipped = 0
        temporaryObjectsCleanedByTest = $true
    })))
    foreach ($item in @(
            @{ Id = "object-storage-network-acl"; EvidenceId = "NET-ACL-20260723" },
            @{ Id = "object-storage-capacity"; EvidenceId = "CAPACITY-20260723" },
            @{ Id = "object-storage-lifecycle"; EvidenceId = "LIFECYCLE-20260723" },
            @{ Id = "object-storage-ha"; EvidenceId = "HA-20260723" }
        )) {
        [void]$results.Add((New-AcceptanceResult -Id $item.Id -Details ([ordered]@{
            evidence = "OPERATOR_ATTESTED"
            evidenceId = $item.EvidenceId
        })))
    }
    return $results.ToArray()
}

function Invoke-Summary {
    param(
        [Parameter(Mandatory = $true)][string[]]$Reports,
        [Parameter(Mandatory = $true)][string]$OutputDirectory,
        [ValidateSet("PASS", "FAIL", "PENDING")][string]$RollbackStatus = "PASS",
        [AllowEmptyString()][string]$RollbackEvidenceId = "ROLLBACK-DRILL-20260723",
        [AllowEmptyString()][string]$RollbackCompletedAt = ([DateTimeOffset]::UtcNow.ToString("o"))
    )
    $failed = $false
    try {
        & $summaryScript -ReportPaths $Reports -RuntimeClusterIds @("46", "50") `
                -ExpectedObjectProvider OSS -MaxReportAgeHours 24 `
                -RollbackStatus $RollbackStatus -RollbackEvidenceId $RollbackEvidenceId `
                -RollbackCompletedAt $RollbackCompletedAt -ResultDirectory $OutputDirectory | Out-Null
    } catch {
        $failed = $true
    }
    $jsonPath = Get-ChildItem -LiteralPath $OutputDirectory -Filter "*.json" | Select-Object -First 1
    Assert-True -Condition ($null -ne $jsonPath) -Message "Summary script should always write a redacted JSON result."
    return [PSCustomObject]@{
        Failed = $failed
        JsonPath = $jsonPath.FullName
        Json = Get-Content -LiteralPath $jsonPath.FullName -Raw | ConvertFrom-Json
        Raw = Get-Content -LiteralPath $jsonPath.FullName -Raw
    }
}

try {
    [void](New-Item -ItemType Directory -Path $temporaryRoot -Force)
    $target46 = "host-sha256:aaaaaaaaaaaa:3306"
    $target50 = "host-sha256:bbbbbbbbbbbb:1521"
    $runtime46 = Write-SourceReport -Name "runtime-46" -NodeRole RELEASE_RUNNER -RuntimeClusterId "46" `
            -Checks @("RuntimeEndpoint") -Results @((New-RuntimeResult -ClusterId "46"))
    $runtime50 = Write-SourceReport -Name "runtime-50" -NodeRole RELEASE_RUNNER -RuntimeClusterId "50" `
            -Checks @("RuntimeEndpoint") -Results @((New-RuntimeResult -ClusterId "50"))
    $oms46 = Write-SourceReport -Name "oms-denied-46" -NodeRole OMS -RuntimeClusterId $null `
            -Checks @("Network") -NetworkExpectation "DENIED" `
            -Results @((New-NetworkResult -Target $target46 -Observed UNREACHABLE -RuntimeClusterId $null))
    $worker46 = Write-SourceReport -Name "worker-allowed-46" -NodeRole WORKER -RuntimeClusterId "46" `
            -Checks @("Network") -NetworkExpectation "ALLOWED" `
            -Results @((New-NetworkResult -Target $target46 -Observed REACHABLE -RuntimeClusterId "46"))
    $oms50 = Write-SourceReport -Name "oms-denied-50" -NodeRole OMS -RuntimeClusterId $null `
            -Checks @("Network") -NetworkExpectation "DENIED" `
            -Results @((New-NetworkResult -Target $target50 -Observed UNREACHABLE -RuntimeClusterId $null))
    $worker50 = Write-SourceReport -Name "worker-allowed-50" -NodeRole WORKER -RuntimeClusterId "50" `
            -Checks @("Network") -NetworkExpectation "ALLOWED" `
            -Results @((New-NetworkResult -Target $target50 -Observed REACHABLE -RuntimeClusterId "50"))
    $object = Write-SourceReport -Name "object-storage" -NodeRole RELEASE_RUNNER -RuntimeClusterId $null `
            -Checks @("ObjectStorage") -Results (New-ObjectResults)
    $allReports = @($runtime46, $runtime50, $oms46, $worker46, $oms50, $worker50, $object)

    $passResult = Invoke-Summary -Reports $allReports -OutputDirectory (Join-Path $temporaryRoot "summary-pass")
    Assert-True -Condition (-not $passResult.Failed -and $passResult.Json.overallStatus -eq "PASS") `
        -Message ("Complete multi-cluster production evidence should pass summary validation. Safe results: " +
            (($passResult.Json.results | ConvertTo-Json -Depth 6 -Compress)))
    Assert-True -Condition ($passResult.Json.sourceReports.Count -eq 7) `
        -Message "Summary should retain only seven redacted source report fingerprints."
    Assert-True -Condition ($passResult.Json.schemaVersion -eq "studio.production-runtime.acceptance-summary.v2" -and
            @($passResult.Json.results | Where-Object { $_.id -eq "production-rollback" -and $_.status -eq "PASS" }).Count -eq 1) `
        -Message "A complete summary must include a successful production rollback evidence result."
    Assert-True -Condition (-not $passResult.Raw.Contains($temporaryRoot)) `
        -Message "Summary must not persist source report paths."

    $missingRollbackResult = Invoke-Summary -Reports $allReports `
            -RollbackStatus PENDING -RollbackEvidenceId "" -RollbackCompletedAt "" `
            -OutputDirectory (Join-Path $temporaryRoot "summary-missing-rollback")
    Assert-True -Condition ($missingRollbackResult.Failed -and
            $missingRollbackResult.Json.overallStatus -eq "FAIL") `
        -Message "Missing production rollback evidence must fail final summary validation."

    $staleRollbackResult = Invoke-Summary -Reports $allReports `
            -RollbackCompletedAt ([DateTimeOffset]::UtcNow.AddHours(-48).ToString("o")) `
            -OutputDirectory (Join-Path $temporaryRoot "summary-stale-rollback")
    Assert-True -Condition ($staleRollbackResult.Failed -and
            $staleRollbackResult.Json.overallStatus -eq "FAIL") `
        -Message "Stale production rollback evidence must fail final summary validation."

    $unsafeRollbackMarker = "https://internal.example.invalid/change"
    $invalidRollbackResult = Invoke-Summary -Reports $allReports `
            -RollbackEvidenceId $unsafeRollbackMarker `
            -OutputDirectory (Join-Path $temporaryRoot "summary-invalid-rollback-id")
    Assert-True -Condition ($invalidRollbackResult.Failed -and
            $invalidRollbackResult.Json.overallStatus -eq "FAIL") `
        -Message "An unsafe production rollback evidence identifier must fail final summary validation."
    Assert-True -Condition (-not $invalidRollbackResult.Raw.Contains($unsafeRollbackMarker)) `
        -Message "Rejected rollback evidence text must not be copied into the redacted summary."

    $missingClusterResult = Invoke-Summary -Reports @($runtime46, $runtime50, $oms46, $worker46, $oms50, $object) `
            -OutputDirectory (Join-Path $temporaryRoot "summary-missing-cluster")
    Assert-True -Condition ($missingClusterResult.Failed -and $missingClusterResult.Json.overallStatus -eq "FAIL") `
        -Message "Missing Worker network evidence for one cluster must fail summary validation."

    $mismatchWorker50 = Write-SourceReport -Name "worker-allowed-50-mismatch" -NodeRole WORKER -RuntimeClusterId "50" `
            -Checks @("Network") -NetworkExpectation "ALLOWED" `
            -Results @((New-NetworkResult -Target "host-sha256:cccccccccccc:1521" -Observed REACHABLE -RuntimeClusterId "50"))
    $mismatchResult = Invoke-Summary -Reports @($runtime46, $runtime50, $oms46, $worker46, $oms50, $mismatchWorker50, $object) `
            -OutputDirectory (Join-Path $temporaryRoot "summary-mismatched-target")
    Assert-True -Condition ($mismatchResult.Failed -and $mismatchResult.Json.overallStatus -eq "FAIL") `
        -Message "Worker and OMS network reports for different targets must not be paired."

    $staleRuntime50 = Write-SourceReport -Name "runtime-50-stale" -NodeRole RELEASE_RUNNER -RuntimeClusterId "50" `
            -Checks @("RuntimeEndpoint") -Results @((New-RuntimeResult -ClusterId "50")) `
            -GeneratedAt ([DateTimeOffset]::UtcNow.AddHours(-48))
    $staleResult = Invoke-Summary -Reports @($runtime46, $staleRuntime50, $oms46, $worker46, $oms50, $worker50, $object) `
            -OutputDirectory (Join-Path $temporaryRoot "summary-stale")
    Assert-True -Condition ($staleResult.Failed -and $staleResult.Json.overallStatus -eq "FAIL") `
        -Message "Stale production evidence must fail summary validation."

    $duplicateReportResult = Invoke-Summary -Reports @($allReports + $runtime46) `
            -OutputDirectory (Join-Path $temporaryRoot "summary-duplicate-report")
    Assert-True -Condition ($duplicateReportResult.Failed -and $duplicateReportResult.Json.overallStatus -eq "FAIL") `
        -Message "Supplying the same source report twice must fail summary validation."

    $duplicateResultRecord = Get-Content -LiteralPath $runtime50 -Raw | ConvertFrom-Json
    $duplicateResultRecord.results = @($duplicateResultRecord.results) + @($duplicateResultRecord.results[0])
    $duplicateResultReport = Write-ReportFixture -Name "runtime-50-duplicate-result" -Record $duplicateResultRecord
    $duplicateResultSummary = Invoke-Summary `
            -Reports @($runtime46, $duplicateResultReport, $oms46, $worker46, $oms50, $worker50, $object) `
            -OutputDirectory (Join-Path $temporaryRoot "summary-duplicate-result")
    Assert-True -Condition ($duplicateResultSummary.Failed -and $duplicateResultSummary.Json.overallStatus -eq "FAIL") `
        -Message "A PASS report with duplicated result IDs must fail summary validation."

    $statusMismatchRecord = Get-Content -LiteralPath $runtime50 -Raw | ConvertFrom-Json
    $statusMismatchRecord.results[0].status = "FAIL"
    $statusMismatchReport = Write-ReportFixture -Name "runtime-50-status-mismatch" -Record $statusMismatchRecord
    $statusMismatchSummary = Invoke-Summary `
            -Reports @($runtime46, $statusMismatchReport, $oms46, $worker46, $oms50, $worker50, $object) `
            -OutputDirectory (Join-Path $temporaryRoot "summary-status-mismatch")
    Assert-True -Condition ($statusMismatchSummary.Failed -and $statusMismatchSummary.Json.overallStatus -eq "FAIL") `
        -Message "A report with overall PASS and an embedded failed result must fail summary validation."

    $slowRuntimeRecord = Get-Content -LiteralPath $runtime50 -Raw | ConvertFrom-Json
    $slowRuntimeRecord.results[0].details.performanceP95Ms = 1500.0
    $slowRuntimeRecord.results[0].details.performanceP99Ms = 1500.0
    $slowRuntimeRecord.results[0].details.performanceMaxMs = 1600.0
    $slowRuntimeReport = Write-ReportFixture -Name "runtime-50-slow-hop" -Record $slowRuntimeRecord
    $slowRuntimeSummary = Invoke-Summary `
            -Reports @($runtime46, $slowRuntimeReport, $oms46, $worker46, $oms50, $worker50, $object) `
            -OutputDirectory (Join-Path $temporaryRoot "summary-slow-runtime-hop")
    Assert-True -Condition ($slowRuntimeSummary.Failed -and $slowRuntimeSummary.Json.overallStatus -eq "FAIL") `
        -Message "A runtime HTTP-hop baseline above its declared p95 threshold must fail summary validation."

    $missingFieldRecord = Get-Content -LiteralPath $runtime50 -Raw | ConvertFrom-Json
    $missingFieldRecord.results[0].details.PSObject.Properties.Remove("responseBodyLimitBytes")
    $missingFieldReport = Write-ReportFixture -Name "runtime-50-missing-field" -Record $missingFieldRecord
    $missingFieldSummary = Invoke-Summary `
            -Reports @($runtime46, $missingFieldReport, $oms46, $worker46, $oms50, $worker50, $object) `
            -OutputDirectory (Join-Path $temporaryRoot "summary-missing-field")
    Assert-True -Condition ($missingFieldSummary.Failed -and $missingFieldSummary.Json.overallStatus -eq "FAIL") `
        -Message "A PASS report missing a required protocol field must fail summary validation."

    $unknownCheckMarker = "C:/production/private-host"
    $unknownCheckRecord = Get-Content -LiteralPath $runtime50 -Raw | ConvertFrom-Json
    $unknownCheckRecord.requestedChecks = @("RuntimeEndpoint", $unknownCheckMarker)
    $unknownCheckReport = Write-ReportFixture -Name "runtime-50-unknown-check" -Record $unknownCheckRecord
    $unknownCheckSummary = Invoke-Summary `
            -Reports @($runtime46, $unknownCheckReport, $oms46, $worker46, $oms50, $worker50, $object) `
            -OutputDirectory (Join-Path $temporaryRoot "summary-unknown-check")
    Assert-True -Condition ($unknownCheckSummary.Failed -and $unknownCheckSummary.Json.overallStatus -eq "FAIL") `
        -Message "Unknown requested checks must fail summary validation."
    Assert-True -Condition (-not $unknownCheckSummary.Raw.Contains($unknownCheckMarker)) `
        -Message "Invalid requested check text must not be copied into the redacted summary."

    $rawEndpointMarker = "https://production-internal.example.invalid/internal/runtime/health"
    $rawEndpointRecord = Get-Content -LiteralPath $runtime50 -Raw | ConvertFrom-Json
    $rawEndpointRecord.results[0].details.endpoint = $rawEndpointMarker
    $rawEndpointReport = Write-ReportFixture -Name "runtime-50-raw-endpoint" -Record $rawEndpointRecord
    $rawEndpointSummary = Invoke-Summary `
            -Reports @($runtime46, $rawEndpointReport, $oms46, $worker46, $oms50, $worker50, $object) `
            -OutputDirectory (Join-Path $temporaryRoot "summary-raw-endpoint")
    Assert-True -Condition ($rawEndpointSummary.Failed -and $rawEndpointSummary.Json.overallStatus -eq "FAIL") `
        -Message "A source report containing a non-redacted endpoint must fail summary validation."
    Assert-True -Condition (-not $rawEndpointSummary.Raw.Contains($rawEndpointMarker)) `
        -Message "A rejected raw endpoint must not be copied into the redacted summary."

    $uncleanObjectRecord = Get-Content -LiteralPath $object -Raw | ConvertFrom-Json
    $objectProtocol = @($uncleanObjectRecord.results | Where-Object { $_.id -eq "object-storage-runtime-protocol" })[0]
    $objectProtocol.details.temporaryObjectsCleanedByTest = $false
    $uncleanObjectReport = Write-ReportFixture -Name "object-storage-not-cleaned" -Record $uncleanObjectRecord
    $uncleanObjectSummary = Invoke-Summary `
            -Reports @($runtime46, $runtime50, $oms46, $worker46, $oms50, $worker50, $uncleanObjectReport) `
            -OutputDirectory (Join-Path $temporaryRoot "summary-object-not-cleaned")
    Assert-True -Condition ($uncleanObjectSummary.Failed -and $uncleanObjectSummary.Json.overallStatus -eq "FAIL") `
        -Message "Object storage evidence must prove that temporary objects were cleaned."

    Write-Host "Studio production acceptance summary tests passed (15/15)."
} finally {
    if (Test-Path -LiteralPath $temporaryRoot) {
        $verifiedRoot = [IO.Path]::GetFullPath($temporaryRoot)
        if ($verifiedRoot.StartsWith($temporaryBase, [StringComparison]::OrdinalIgnoreCase) -and
                [IO.Path]::GetFileName($verifiedRoot).StartsWith("studio-production-summary-test-", [StringComparison]::Ordinal)) {
            Remove-Item -LiteralPath $verifiedRoot -Recurse -Force
        }
    }
}
