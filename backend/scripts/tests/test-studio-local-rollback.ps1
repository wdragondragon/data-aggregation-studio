$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$rollbackScript = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\test-studio-local-rollback.ps1"))
$listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
$process = $null

try {
    $listener.Start()
    $occupiedPort = ([Net.IPEndPoint]$listener.LocalEndpoint).Port
    $command = "try { & '" + $rollbackScript.Replace("'", "''") +
        "' -ServerPort $occupiedPort; exit 0 } catch { " +
        "[Console]::Error.WriteLine(`$_.Exception.Message); exit 1 }"
    $encodedCommand = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($command))

    $startInfo = [Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = "powershell.exe"
    $startInfo.Arguments = "-NoLogo -NoProfile -ExecutionPolicy Bypass -EncodedCommand $encodedCommand"
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true

    $process = [Diagnostics.Process]::Start($startInfo)
    $stdoutTask = $process.StandardOutput.ReadToEndAsync()
    $stderrTask = $process.StandardError.ReadToEndAsync()
    $process.WaitForExit()
    $output = $stdoutTask.GetAwaiter().GetResult() + "`n" + $stderrTask.GetAwaiter().GetResult()

    if ($process.ExitCode -eq 0) {
        throw "An occupied rollback Server port must fail the drill."
    }
    if (-not $output.Contains("rollback drill Server port is already in use")) {
        throw "The rollback drill should explain the occupied-port failure."
    }
    if (@(& docker ps -a --filter "name=studio-p0mc02-rollback" --format "{{.Names}}").Count -ne 0) {
        throw "An offline rollback preflight failure must not leave a container."
    }
    if (@(git worktree list --porcelain | Select-String "studio-p0mc02-rollback-head-").Count -ne 0) {
        throw "An offline rollback preflight failure must not leave a worktree."
    }

    Write-Host "Studio local rollback failure-closure tests passed (1/1)."
} finally {
    if ($null -ne $process) {
        $process.Dispose()
    }
    $listener.Stop()
}
