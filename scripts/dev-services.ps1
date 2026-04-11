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

function Ensure-Directory {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) {
        New-Item -ItemType Directory -Path $Path -Force | Out-Null
    }
}

function New-ServiceDefinition {
    param(
        [string]$Name,
        [string]$Workdir,
        [string]$Command,
        [int]$Port,
        [string]$PrimaryUrl,
        [string[]]$AlternateUrls = @()
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
        PidFile       = Join-Path $serviceRuntimeDir "service.pid"
        StdOutLog     = Join-Path $serviceRuntimeDir "stdout.log"
        StdErrLog     = Join-Path $serviceRuntimeDir "stderr.log"
    }
}

Ensure-Directory -Path $RuntimeRoot

$frontendWorkdir = Join-Path $StudioRoot "frontend"
$backendWorkdir = Join-Path $StudioRoot "backend"

$serviceDefinitions = @{
    frontend = New-ServiceDefinition `
        -Name "frontend" `
        -Workdir $frontendWorkdir `
        -Command "Set-Location '$frontendWorkdir'; npm run dev:web" `
        -Port 5173 `
        -PrimaryUrl "http://localhost:5173" `
        -AlternateUrls @("http://127.0.0.1:5173")
    backend = New-ServiceDefinition `
        -Name "backend" `
        -Workdir $backendWorkdir `
        -Command "Set-Location '$backendWorkdir'; powershell -ExecutionPolicy Bypass -File '$backendWorkdir\\scripts\\upgrade-studio-schema.ps1'; if (`$LASTEXITCODE -ne 0) { exit `$LASTEXITCODE }; mvn -pl studio-server spring-boot:run" `
        -Port 18080 `
        -PrimaryUrl "http://127.0.0.1:18080" `
        -AlternateUrls @("http://localhost:18080")
    worker = New-ServiceDefinition `
        -Name "worker" `
        -Workdir $backendWorkdir `
        -Command "Set-Location '$backendWorkdir'; mvn -pl studio-worker spring-boot:run" `
        -Port 18081 `
        -PrimaryUrl "http://127.0.0.1:18081" `
        -AlternateUrls @("http://localhost:18081")
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

function Read-ServicePid {
    param($Service)

    if (-not (Test-Path -LiteralPath $Service.PidFile)) {
        return $null
    }

    $raw = (Get-Content -LiteralPath $Service.PidFile -ErrorAction SilentlyContinue | Select-Object -First 1).Trim()
    if (-not $raw) {
        return $null
    }

    try {
        return [int]$raw
    } catch {
        return $null
    }
}

function Get-ServiceProcess {
    param($Service)

    $servicePid = Read-ServicePid -Service $Service
    if (-not $servicePid) {
        return $null
    }

    try {
        return Get-Process -Id $servicePid -ErrorAction Stop
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
        [int]$TimeoutSeconds = 120
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if (Test-TcpPort -Port $Port) {
            return $true
        }
        Start-Sleep -Seconds 1
    }
    return $false
}

function Start-ManagedService {
    param($Service)

    $process = Get-ServiceProcess -Service $Service
    if ($process -and -not $process.HasExited) {
        Write-Host "[$($Service.Name)] already running (PID $($process.Id)) at $($Service.PrimaryUrl)"
        return
    }

    Remove-ServicePid -Service $Service

    foreach ($logFile in @($Service.StdOutLog, $Service.StdErrLog)) {
        if (Test-Path -LiteralPath $logFile) {
            Remove-Item -LiteralPath $logFile -Force -ErrorAction SilentlyContinue
        }
    }

    $process = Start-Process `
        -FilePath "powershell.exe" `
        -ArgumentList @(
            "-NoLogo",
            "-NoProfile",
            "-ExecutionPolicy", "Bypass",
            "-Command", $Service.Command
        ) `
        -WorkingDirectory $Service.Workdir `
        -RedirectStandardOutput $Service.StdOutLog `
        -RedirectStandardError $Service.StdErrLog `
        -WindowStyle Hidden `
        -PassThru

    Set-Content -LiteralPath $Service.PidFile -Value $process.Id -Encoding ASCII

    Write-Host "[$($Service.Name)] starting (PID $($process.Id)) ..."

    if (Wait-PortUp -Port $Service.Port) {
        Write-Host "[$($Service.Name)] ready at $($Service.PrimaryUrl)"
        return
    }

    $stillRunning = Get-ServiceProcess -Service $Service
    if ($stillRunning -and -not $stillRunning.HasExited) {
        throw "[$($Service.Name)] did not expose port $($Service.Port) in time. Check logs: $($Service.StdOutLog)"
    }

    throw "[$($Service.Name)] exited during startup. Check logs: $($Service.StdOutLog) and $($Service.StdErrLog)"
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
