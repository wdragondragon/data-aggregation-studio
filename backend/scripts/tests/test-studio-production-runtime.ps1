$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$acceptanceScript = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\test-studio-production-runtime.ps1"))
$temporaryBase = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$temporaryRoot = [IO.Path]::GetFullPath((Join-Path $temporaryBase ("studio-runtime-script-test-" + [Guid]::NewGuid().ToString("N"))))
$savedEnvironment = @{}
$environmentNames = @(
    "STUDIO_ACCEPTANCE_RUNTIME_BASE_URL",
    "STUDIO_ACCEPTANCE_RUNTIME_CLUSTER_ID",
    "STUDIO_ACCEPTANCE_RUNTIME_HEADERS_JSON",
    "STUDIO_ACCEPTANCE_ALLOW_HTTP",
    "STUDIO_ACCEPTANCE_RUNTIME_PERFORMANCE_SAMPLES",
    "STUDIO_ACCEPTANCE_RUNTIME_MAX_P95_MS",
    "STUDIO_ACCEPTANCE_DATASOURCE_HOST",
    "STUDIO_ACCEPTANCE_DATASOURCE_PORT",
    "STUDIO_ACCEPTANCE_OBJECT_PROVIDER",
    "STUDIO_ACCEPTANCE_OBJECT_ENDPOINT",
    "STUDIO_ACCEPTANCE_OBJECT_ACCESS_KEY",
    "STUDIO_ACCEPTANCE_OBJECT_SECRET_KEY",
    "STUDIO_ACCEPTANCE_OBJECT_BUCKET",
    "STUDIO_ACCEPTANCE_OBJECT_REGION",
    "STUDIO_ACCEPTANCE_OBJECT_CREATE_BUCKET",
    "STUDIO_ACCEPTANCE_OBJECT_NETWORK_ACL_STATUS",
    "STUDIO_ACCEPTANCE_OBJECT_NETWORK_ACL_EVIDENCE_ID",
    "STUDIO_ACCEPTANCE_OBJECT_CAPACITY_STATUS",
    "STUDIO_ACCEPTANCE_OBJECT_CAPACITY_EVIDENCE_ID",
    "STUDIO_ACCEPTANCE_OBJECT_LIFECYCLE_STATUS",
    "STUDIO_ACCEPTANCE_OBJECT_LIFECYCLE_EVIDENCE_ID",
    "STUDIO_ACCEPTANCE_OBJECT_HA_STATUS",
    "STUDIO_ACCEPTANCE_OBJECT_HA_EVIDENCE_ID",
    "STUDIO_ACCEPTANCE_MAVEN_COMMAND",
    "STUDIO_INTERNAL_API_TOKEN"
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

function Get-FreeTcpPort {
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    $listener.Start()
    try {
        return ([Net.IPEndPoint]$listener.LocalEndpoint).Port
    } finally {
        $listener.Stop()
    }
}

function Start-RuntimeMockJob {
    param(
        [Parameter(Mandatory = $true)][int]$Port,
        [Parameter(Mandatory = $true)][string]$ExpectedToken,
        [Parameter(Mandatory = $true)][string]$ReadyFile,
        [int]$RequestCount = 2,
        [switch]$LeakSecret
    )
    return Start-Job -ArgumentList $Port, $ExpectedToken, $ReadyFile, $RequestCount, ([bool]$LeakSecret) -ScriptBlock {
        param($Port, $ExpectedToken, $ReadyFile, $RequestCount, $LeakSecret)
        $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $Port)
        $listener.Start()
        [IO.File]::WriteAllText($ReadyFile, "ready")
        try {
            for ($index = 0; $index -lt $RequestCount; $index++) {
                $client = $listener.AcceptTcpClient()
                try {
                    $stream = $client.GetStream()
                    $reader = [IO.StreamReader]::new($stream, [Text.Encoding]::ASCII, $false, 1024, $true)
                    $requestLine = $reader.ReadLine()
                    $headers = @{}
                    while ($true) {
                        $line = $reader.ReadLine()
                        if ([string]::IsNullOrEmpty($line)) {
                            break
                        }
                        $separator = $line.IndexOf(":")
                        if ($separator -gt 0) {
                            $headers[$line.Substring(0, $separator).Trim()] = $line.Substring($separator + 1).Trim()
                        }
                    }
                    $provided = [string]$headers["X-Studio-Internal-Token"]
                    if ($provided -eq $ExpectedToken) {
                        $status = "200 OK"
                        $marker = "X-Studio-Runtime-Response: AUTHENTICATED`r`n"
                        $body = $(if ($LeakSecret) { $ExpectedToken } else { '{"data":"OK"}' })
                    } else {
                        $status = "401 Unauthorized"
                        $marker = "X-Studio-Internal-Error: INTERNAL_AUTHENTICATION`r`n"
                        $body = '{"message":"Invalid internal API token"}'
                    }
                    $bodyBytes = [Text.Encoding]::UTF8.GetBytes($body)
                    $responseHead = "HTTP/1.1 $status`r`n$marker" +
                            "Content-Type: application/json`r`nContent-Length: $($bodyBytes.Length)`r`nConnection: close`r`n`r`n"
                    $headBytes = [Text.Encoding]::ASCII.GetBytes($responseHead)
                    $stream.Write($headBytes, 0, $headBytes.Length)
                    $stream.Write($bodyBytes, 0, $bodyBytes.Length)
                    $stream.Flush()
                    [pscustomobject]@{
                        requestLine = $requestLine
                        headerNames = @($headers.Keys | Sort-Object)
                    }
                } finally {
                    $client.Dispose()
                }
            }
        } finally {
            $listener.Stop()
        }
    }
}

function Wait-ReadyFile {
    param([Parameter(Mandatory = $true)][string]$Path)
    $deadline = [DateTime]::UtcNow.AddSeconds(10)
    while (-not (Test-Path -LiteralPath $Path)) {
        if ([DateTime]::UtcNow -ge $deadline) {
            throw "Runtime mock did not become ready."
        }
        Start-Sleep -Milliseconds 50
    }
}

try {
    [void](New-Item -ItemType Directory -Path $temporaryRoot -Force)
    foreach ($name in $environmentNames) {
        $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, [EnvironmentVariableTarget]::Process)
    }
    $internalToken = "test-internal-token-7f6d91"
    $transportToken = "test-transport-token-c318a4"
    $cookieValue = "test-cookie-value-e142b5"
    [Environment]::SetEnvironmentVariable("STUDIO_INTERNAL_API_TOKEN", $internalToken, "Process")
    [Environment]::SetEnvironmentVariable("STUDIO_ACCEPTANCE_RUNTIME_CLUSTER_ID", "50", "Process")
    [Environment]::SetEnvironmentVariable("STUDIO_ACCEPTANCE_RUNTIME_HEADERS_JSON",
            ('{"X-SLB-Access-Token":"' + $transportToken + '","Cookie":"' + $cookieValue + '"}'), "Process")
    [Environment]::SetEnvironmentVariable("STUDIO_ACCEPTANCE_ALLOW_HTTP", "true", "Process")
    [Environment]::SetEnvironmentVariable("STUDIO_ACCEPTANCE_RUNTIME_PERFORMANCE_SAMPLES", "5", "Process")
    [Environment]::SetEnvironmentVariable("STUDIO_ACCEPTANCE_RUNTIME_MAX_P95_MS", "5000", "Process")

    $passPort = Get-FreeTcpPort
    $passReady = Join-Path $temporaryRoot "runtime-pass.ready"
    $passJob = Start-RuntimeMockJob -Port $passPort -ExpectedToken $internalToken -ReadyFile $passReady -RequestCount 6
    Wait-ReadyFile -Path $passReady
    [Environment]::SetEnvironmentVariable("STUDIO_ACCEPTANCE_RUNTIME_BASE_URL", "http://127.0.0.1:$passPort", "Process")
    $passDirectory = Join-Path $temporaryRoot "runtime-pass"
    try {
        & $acceptanceScript -Checks RuntimeEndpoint -NodeRole RELEASE_RUNNER `
                -ResultDirectory $passDirectory -NodeLabel "offline-runtime-pass" | Out-Null
    } catch {
        $diagnosticPath = Get-ChildItem -LiteralPath $passDirectory -Filter "*.json" -ErrorAction SilentlyContinue | Select-Object -First 1
        $diagnostic = $(if ($null -eq $diagnosticPath) { "no redacted report" } else { Get-Content -LiteralPath $diagnosticPath.FullName -Raw })
        throw "Marked runtime endpoint unexpectedly failed. Redacted diagnostic: $diagnostic"
    }
    [void](Wait-Job -Job $passJob -Timeout 10)
    $requests = @(Receive-Job -Job $passJob)
    Remove-Job -Job $passJob -Force
    Assert-True -Condition ($requests.Count -eq 6) `
        -Message "Runtime acceptance did not make the authentication and performance probes."
    $passJsonPath = Get-ChildItem -LiteralPath $passDirectory -Filter "*.json" | Select-Object -First 1
    $passJson = Get-Content -LiteralPath $passJsonPath.FullName -Raw
    $passRecord = $passJson | ConvertFrom-Json
    Assert-True -Condition ($passRecord.overallStatus -eq "PASS") -Message "Marked runtime endpoint should pass."
    Assert-True -Condition ($passRecord.schemaVersion -eq "studio.production-runtime.acceptance.v2") `
        -Message "Runtime report should use the role-aware acceptance schema."
    Assert-True -Condition ($passRecord.nodeRole -eq "RELEASE_RUNNER" -and $passRecord.runtimeClusterId -eq "50") `
        -Message "Runtime report should retain the non-secret node role and cluster ID."
    $runtimeResult = @($passRecord.results | Where-Object { $_.id -eq "runtime-endpoint-protocol" })[0]
    Assert-True -Condition ($runtimeResult.details.performanceSampleCount -eq 5 -and
            $runtimeResult.details.performanceErrors -eq 0 -and
            $runtimeResult.details.performanceP95Ms -le $runtimeResult.details.performanceMaxP95Ms) `
        -Message "Runtime report should contain a passing HTTP-hop latency baseline."
    Assert-True -Condition ($passJson.Contains("X-SLB-Access-Token")) -Message "Report should retain transport Header names."
    Assert-True -Condition (-not $passJson.Contains($internalToken)) -Message "Report leaked the internal token."
    Assert-True -Condition (-not $passJson.Contains($transportToken)) -Message "Report leaked the transport token."
    Assert-True -Condition (-not $passJson.Contains($cookieValue)) -Message "Report leaked the Cookie value."

    $leakPort = Get-FreeTcpPort
    $leakReady = Join-Path $temporaryRoot "runtime-leak.ready"
    $leakJob = Start-RuntimeMockJob -Port $leakPort -ExpectedToken $internalToken -ReadyFile $leakReady `
            -RequestCount 1 -LeakSecret
    Wait-ReadyFile -Path $leakReady
    [Environment]::SetEnvironmentVariable("STUDIO_ACCEPTANCE_RUNTIME_BASE_URL", "http://127.0.0.1:$leakPort", "Process")
    $leakDirectory = Join-Path $temporaryRoot "runtime-leak"
    $leakFailed = $false
    try {
        & $acceptanceScript -Checks RuntimeEndpoint -NodeRole RELEASE_RUNNER `
                -ResultDirectory $leakDirectory -NodeLabel "offline-runtime-leak" | Out-Null
    } catch {
        $leakFailed = $true
    }
    [void](Wait-Job -Job $leakJob -Timeout 10)
    Receive-Job -Job $leakJob | Out-Null
    Remove-Job -Job $leakJob -Force
    Assert-True -Condition $leakFailed -Message "A response that echoes a secret must fail acceptance."
    $leakJsonPath = Get-ChildItem -LiteralPath $leakDirectory -Filter "*.json" | Select-Object -First 1
    $leakJson = Get-Content -LiteralPath $leakJsonPath.FullName -Raw
    Assert-True -Condition (-not $leakJson.Contains($internalToken)) -Message "Failed report leaked the echoed internal token."

    $networkListener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    $networkListener.Start()
    $allowedPort = ([Net.IPEndPoint]$networkListener.LocalEndpoint).Port
    [Environment]::SetEnvironmentVariable("STUDIO_ACCEPTANCE_DATASOURCE_HOST", "127.0.0.1", "Process")
    [Environment]::SetEnvironmentVariable("STUDIO_ACCEPTANCE_DATASOURCE_PORT", [string]$allowedPort, "Process")
    $allowedDirectory = Join-Path $temporaryRoot "network-allowed"
    & $acceptanceScript -Checks Network -NetworkExpectation Allowed -ResultDirectory $allowedDirectory `
            -NodeRole WORKER -NodeLabel "offline-worker-network" | Out-Null
    $networkListener.Stop()
    $allowedRecord = (Get-Content -LiteralPath ((Get-ChildItem -LiteralPath $allowedDirectory -Filter "*.json").FullName) -Raw) | ConvertFrom-Json
    Assert-True -Condition ($allowedRecord.overallStatus -eq "PASS") -Message "Reachable Worker network target should pass."

    $deniedPort = Get-FreeTcpPort
    [Environment]::SetEnvironmentVariable("STUDIO_ACCEPTANCE_DATASOURCE_PORT", [string]$deniedPort, "Process")
    $deniedDirectory = Join-Path $temporaryRoot "network-denied"
    & $acceptanceScript -Checks Network -NetworkExpectation Denied -ResultDirectory $deniedDirectory `
            -NodeRole OMS -NodeLabel "offline-server-network" | Out-Null
    $deniedRecord = (Get-Content -LiteralPath ((Get-ChildItem -LiteralPath $deniedDirectory -Filter "*.json").FullName) -Raw) | ConvertFrom-Json
    Assert-True -Condition ($deniedRecord.overallStatus -eq "PASS") -Message "Blocked Server network target should pass."

    $fakeMaven = Join-Path $temporaryRoot "fake-maven.ps1"
$fakeMavenSource = @'
$suffixArgument = @($args | Where-Object { $_ -like "-Dsurefire.reportNameSuffix=*" }) | Select-Object -First 1
if ($null -eq $suffixArgument -or $env:STUDIO_IT_OBJECT_PROVIDER -ne "OSS" -or
        $env:STUDIO_IT_OBJECT_REGION -ne "cn-test" -or $env:STUDIO_IT_OBJECT_CREATE_BUCKET -ne "false") {
    exit 2
}
$suffix = $suffixArgument.Substring("-Dsurefire.reportNameSuffix=".Length)
$reportDirectory = Join-Path (Get-Location) "studio-test\target\surefire-reports"
[void](New-Item -ItemType Directory -Path $reportDirectory -Force)
$xmlPath = Join-Path $reportDirectory "TEST-com.jdragon.studio.test.SharedObjectStorageRuntimeIT-$suffix.xml"
[IO.File]::WriteAllText($xmlPath, '<testsuite name="SharedObjectStorageRuntimeIT" tests="1" failures="0" errors="0" skipped="0"></testsuite>')
exit 0
'@
    [IO.File]::WriteAllText($fakeMaven, $fakeMavenSource, [Text.UTF8Encoding]::new($false))
    $objectAccessKey = "test-object-access-697c81"
    $objectSecretKey = "test-object-secret-a642f5"
    [Environment]::SetEnvironmentVariable("STUDIO_ACCEPTANCE_OBJECT_PROVIDER", "OSS", "Process")
    [Environment]::SetEnvironmentVariable("STUDIO_ACCEPTANCE_OBJECT_ENDPOINT", "http://object.test.invalid:9000", "Process")
    [Environment]::SetEnvironmentVariable("STUDIO_ACCEPTANCE_OBJECT_ACCESS_KEY", $objectAccessKey, "Process")
    [Environment]::SetEnvironmentVariable("STUDIO_ACCEPTANCE_OBJECT_SECRET_KEY", $objectSecretKey, "Process")
    [Environment]::SetEnvironmentVariable("STUDIO_ACCEPTANCE_OBJECT_BUCKET", "test-runtime-bucket-cc391a", "Process")
    [Environment]::SetEnvironmentVariable("STUDIO_ACCEPTANCE_OBJECT_REGION", "cn-test", "Process")
    [Environment]::SetEnvironmentVariable("STUDIO_ACCEPTANCE_OBJECT_CREATE_BUCKET", "false", "Process")
    [Environment]::SetEnvironmentVariable("STUDIO_ACCEPTANCE_OBJECT_NETWORK_ACL_STATUS", "PASS", "Process")
    [Environment]::SetEnvironmentVariable("STUDIO_ACCEPTANCE_OBJECT_CAPACITY_STATUS", "PASS", "Process")
    [Environment]::SetEnvironmentVariable("STUDIO_ACCEPTANCE_OBJECT_LIFECYCLE_STATUS", "PASS", "Process")
    [Environment]::SetEnvironmentVariable("STUDIO_ACCEPTANCE_OBJECT_HA_STATUS", "PASS", "Process")
    [Environment]::SetEnvironmentVariable("STUDIO_ACCEPTANCE_MAVEN_COMMAND", $fakeMaven, "Process")

    $missingEvidenceDirectory = Join-Path $temporaryRoot "object-storage-missing-evidence"
    $missingEvidenceFailed = $false
    try {
        & $acceptanceScript -Checks ObjectStorage -NodeRole RELEASE_RUNNER `
                -ResultDirectory $missingEvidenceDirectory -NodeLabel "offline-object-missing-evidence" `
                -RequireComplete | Out-Null
    } catch {
        $missingEvidenceFailed = $true
    }
    Assert-True -Condition $missingEvidenceFailed `
        -Message "Manual PASS without traceable evidence IDs must fail complete acceptance."
    $missingEvidenceRecord = (Get-Content -LiteralPath `
            ((Get-ChildItem -LiteralPath $missingEvidenceDirectory -Filter "*.json").FullName) -Raw) | ConvertFrom-Json
    Assert-True -Condition ($missingEvidenceRecord.overallStatus -eq "FAIL") `
        -Message "Missing manual evidence IDs should produce a failed redacted report."

    [Environment]::SetEnvironmentVariable("STUDIO_ACCEPTANCE_OBJECT_NETWORK_ACL_EVIDENCE_ID", "NET-ACL-20260723", "Process")
    [Environment]::SetEnvironmentVariable("STUDIO_ACCEPTANCE_OBJECT_CAPACITY_EVIDENCE_ID", "CAPACITY-20260723", "Process")
    [Environment]::SetEnvironmentVariable("STUDIO_ACCEPTANCE_OBJECT_LIFECYCLE_EVIDENCE_ID", "LIFECYCLE-20260723", "Process")
    [Environment]::SetEnvironmentVariable("STUDIO_ACCEPTANCE_OBJECT_HA_EVIDENCE_ID", "HA-20260723", "Process")

    $unsafeEvidenceMarker = "https://internal.example.invalid/change"
    [Environment]::SetEnvironmentVariable("STUDIO_ACCEPTANCE_OBJECT_NETWORK_ACL_EVIDENCE_ID", $unsafeEvidenceMarker, "Process")
    $unsafeEvidenceDirectory = Join-Path $temporaryRoot "object-storage-unsafe-evidence"
    $unsafeEvidenceFailed = $false
    try {
        & $acceptanceScript -Checks ObjectStorage -NodeRole RELEASE_RUNNER `
                -ResultDirectory $unsafeEvidenceDirectory -NodeLabel "offline-object-unsafe-evidence" `
                -RequireComplete | Out-Null
    } catch {
        $unsafeEvidenceFailed = $true
    }
    Assert-True -Condition $unsafeEvidenceFailed `
        -Message "A URL must not be accepted as a manual evidence identifier."
    $unsafeEvidenceJson = Get-Content -LiteralPath `
            ((Get-ChildItem -LiteralPath $unsafeEvidenceDirectory -Filter "*.json").FullName) -Raw
    Assert-True -Condition (-not $unsafeEvidenceJson.Contains($unsafeEvidenceMarker)) `
        -Message "Rejected evidence URL must not be copied into the redacted report."
    [Environment]::SetEnvironmentVariable("STUDIO_ACCEPTANCE_OBJECT_NETWORK_ACL_EVIDENCE_ID", "NET-ACL-20260723", "Process")

    $objectDirectory = Join-Path $temporaryRoot "object-storage"
    try {
        & $acceptanceScript -Checks ObjectStorage -NodeRole RELEASE_RUNNER -ResultDirectory $objectDirectory `
                -NodeLabel "offline-object-orchestration" -RequireComplete | Out-Null
    } catch {
        $diagnosticPath = Get-ChildItem -LiteralPath $objectDirectory -Filter "*.json" -ErrorAction SilentlyContinue | Select-Object -First 1
        $diagnostic = $(if ($null -eq $diagnosticPath) { "no redacted report" } else { Get-Content -LiteralPath $diagnosticPath.FullName -Raw })
        throw "Object storage orchestration fixture unexpectedly failed. Redacted diagnostic: $diagnostic"
    }
    $objectJson = Get-Content -LiteralPath ((Get-ChildItem -LiteralPath $objectDirectory -Filter "*.json").FullName) -Raw
    $objectRecord = $objectJson | ConvertFrom-Json
    Assert-True -Condition ($objectRecord.overallStatus -eq "PASS") -Message "Object storage orchestration fixture should pass."
    Assert-True -Condition ($objectRecord.results[0].details.provider -eq "OSS") -Message "Object report should retain the non-secret provider."
    Assert-True -Condition (-not $objectJson.Contains($objectAccessKey)) -Message "Object report leaked the access key."
    Assert-True -Condition (-not $objectJson.Contains($objectSecretKey)) -Message "Object report leaked the secret key."

    Write-Host "Production runtime acceptance script tests passed (7/7)."
} finally {
    foreach ($name in $environmentNames) {
        [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], [EnvironmentVariableTarget]::Process)
    }
    Get-Job | Where-Object { $_.State -ne "Completed" } | Stop-Job -ErrorAction SilentlyContinue
    Get-Job | Remove-Job -Force -ErrorAction SilentlyContinue
    if (Test-Path -LiteralPath $temporaryRoot) {
        $verifiedRoot = [IO.Path]::GetFullPath($temporaryRoot)
        if ($verifiedRoot.StartsWith($temporaryBase, [StringComparison]::OrdinalIgnoreCase) -and
                [IO.Path]::GetFileName($verifiedRoot).StartsWith("studio-runtime-script-test-", [StringComparison]::Ordinal)) {
            Remove-Item -LiteralPath $verifiedRoot -Recurse -Force
        }
    }
}
