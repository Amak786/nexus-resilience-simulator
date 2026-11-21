<#
TRACE sensor for Windows - uploads connection METADATA of this computer to a TRACE server.

What it reads:  the local TCP connection table (Get-NetTCPConnection): local/remote address and port, state,
                and - only with -IncludeProcess - the owning process name. No packet contents, no URLs, no DNS names.
What it sends:  batches of rows to POST <Server>/api/ingest/connections with "Authorization: Bearer <token>".
Run only on machines you own or are authorised to monitor.

Usage (PowerShell):
  $env:TRACE_INGEST_TOKEN = "<the server's TRACE_INGEST_TOKEN>"
  .\trace-sensor.ps1 -Server http://localhost:8080 -SensorId my-laptop -IncludeProcess
  .\trace-sensor.ps1 -Server https://nexus-resilience.onrender.com -SensorId lab-pc-1 -IntervalSeconds 15
  .\trace-sensor.ps1 -Once          # one batch, then exit (useful for testing)
#>
param(
    [string]$Server = "http://localhost:8080",
    [string]$SensorId = "sensor-1",
    [int]$IntervalSeconds = 15,
    [switch]$IncludeProcess,
    [switch]$Once
)

$ErrorActionPreference = "Stop"
$token = $env:TRACE_INGEST_TOKEN
if ([string]::IsNullOrWhiteSpace($token)) { Write-Error "Set TRACE_INGEST_TOKEN in the environment first."; exit 2 }
if ($SensorId -notmatch '^[A-Za-z0-9._-]{1,40}$') { Write-Error "SensorId: 1-40 letters, digits, '.', '_' or '-'"; exit 2 }
$uri = ($Server.TrimEnd('/')) + "/api/ingest/connections"
$version = "trace-sensor-ps/1.0"

function Get-Rows {
    $now = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
    $procNames = @{}
    if ($IncludeProcess) { Get-Process | ForEach-Object { $procNames[$_.Id] = $_.ProcessName } }
    Get-NetTCPConnection -ErrorAction SilentlyContinue |
        Where-Object { $_.State -in @('Established', 'SynSent', 'CloseWait') -and $_.RemoteAddress -notin @('127.0.0.1', '::1', '0.0.0.0', '::') } |
        Select-Object -First 2000 |
        ForEach-Object {
            $row = [ordered]@{
                observedAt    = $now
                protocol      = "TCP"
                localAddress  = $_.LocalAddress
                localPort     = [int]$_.LocalPort
                remoteAddress = $_.RemoteAddress
                remotePort    = [int]$_.RemotePort
                state         = [string]$_.State
            }
            if ($IncludeProcess -and $procNames.ContainsKey([int]$_.OwningProcess)) {
                $name = ($procNames[[int]$_.OwningProcess] -replace '[^A-Za-z0-9 ._()+-]', '')
                if ($name.Length -gt 0) { $row.process = $name.Substring(0, [Math]::Min(60, $name.Length)) }
            }
            $row
        }
}

function Send-Batch($rows) {
    $batch = [ordered]@{
        sensorId     = $SensorId
        batchId      = [guid]::NewGuid().ToString()
        source       = "windows-get-nettcpconnection"
        agentVersion = $version
        sentAt       = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
        connections  = @($rows)
    }
    $json = $batch | ConvertTo-Json -Depth 4 -Compress
    $headers = @{ Authorization = "Bearer $token" }
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        try {
            # same batchId on retry: the server ignores duplicates, so a timeout never double-counts
            $r = Invoke-RestMethod -Method Post -Uri $uri -Headers $headers -ContentType "application/json" -Body $json -TimeoutSec 30
            Write-Host ("{0} sent {1} rows: accepted {2}, new flows {3}, rejected {4}{5}" -f (Get-Date -Format HH:mm:ss), $r.received, $r.accepted, $r.newFlows, $r.rejected, $(if ($r.duplicateBatch) { " (duplicate batch ignored)" } else { "" }))
            return
        } catch {
            $code = $_.Exception.Response.StatusCode.value__
            if ($code -in @(400, 401, 403, 413, 503)) { Write-Warning "Server refused the batch (HTTP $code): $($_.ErrorDetails.Message)"; return }
            Write-Warning "Attempt $attempt failed: $($_.Exception.Message)"
            Start-Sleep -Seconds ([Math]::Pow(2, $attempt))
        }
    }
    Write-Warning "Batch dropped after 3 attempts; the next interval will send fresh data."
}

Write-Host "TRACE sensor '$SensorId' -> $uri (every $IntervalSeconds s; process names: $([bool]$IncludeProcess)). Ctrl+C to stop."
do {
    $rows = @(Get-Rows)
    if ($rows.Count -gt 0) { Send-Batch $rows } else { Write-Host "no external TCP connections right now" }
    if (-not $Once) { Start-Sleep -Seconds $IntervalSeconds }
} while (-not $Once)
