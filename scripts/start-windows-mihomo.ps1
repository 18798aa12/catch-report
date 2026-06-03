[CmdletBinding()]
param(
    [string]$ConfigFile = "",
    [string]$SubscriptionUrl = "",
    [int]$MixedPort = 7890,
    [switch]$Stop
)

$ErrorActionPreference = "Stop"
. "$PSScriptRoot\dev-env.ps1"

$installDir = "D:\Dev\Mihomo\windows"
$runtimeDir = Join-Path $installDir "runtime"
$exe = Join-Path $installDir "mihomo.exe"
$configPath = Join-Path $runtimeDir "config.yaml"
$pidPath = Join-Path $runtimeDir "mihomo.pid"
$stdoutPath = Join-Path $runtimeDir "mihomo.out.log"
$stderrPath = Join-Path $runtimeDir "mihomo.err.log"

function Test-PortOpen {
    param([int]$Port)
    $client = [System.Net.Sockets.TcpClient]::new()
    try {
        $task = $client.ConnectAsync("127.0.0.1", $Port)
        return $task.Wait(500) -and $client.Connected
    }
    finally {
        $client.Dispose()
    }
}

function Stop-Mihomo {
    if (-not (Test-Path -LiteralPath $pidPath)) {
        Write-Host "No mihomo pid file was found."
        return
    }

    $pidText = (Get-Content -Raw -LiteralPath $pidPath).Trim()
    if (-not $pidText) {
        Remove-Item -LiteralPath $pidPath -Force
        return
    }

    $process = Get-Process -Id ([int]$pidText) -ErrorAction SilentlyContinue
    if ($process) {
        Stop-Process -Id $process.Id -Force
        Write-Host "Stopped mihomo pid $($process.Id)."
    }
    Remove-Item -LiteralPath $pidPath -Force -ErrorAction SilentlyContinue
}

function Normalize-MihomoConfig {
    param(
        [string]$RawConfig,
        [int]$Port
    )

    $keysToReplace = @{
        "mixed-port" = $true
        "port" = $true
        "socks-port" = $true
        "redir-port" = $true
        "tproxy-port" = $true
        "bind-address" = $true
        "allow-lan" = $true
        "external-controller" = $true
        "secret" = $true
        "geo-auto-update" = $true
        "geo-update-interval" = $true
    }
    $blocksToRemove = @{
        "tun" = $true
        "geox-url" = $true
    }

    $lines = ($RawConfig.TrimStart([char]0xFEFF) -split "\r?\n", 0)
    $filtered = [System.Collections.Generic.List[string]]::new()
    $skipIndentedBlock = $false

    foreach ($line in $lines) {
        $trimmedEnd = $line.TrimEnd()
        $trimmedStart = $trimmedEnd.TrimStart()
        $isTopLevel = $trimmedEnd.Length -gt 0 -and -not [char]::IsWhiteSpace($trimmedEnd[0])

        if ($skipIndentedBlock) {
            if (-not $isTopLevel -or $trimmedStart.StartsWith("#")) {
                continue
            }
            $skipIndentedBlock = $false
        }

        if ($isTopLevel) {
            $colonIndex = $trimmedStart.IndexOf(":")
            $key = if ($colonIndex -ge 0) { $trimmedStart.Substring(0, $colonIndex).Trim() } else { "" }
            if ($keysToReplace.ContainsKey($key)) {
                continue
            }
            if ($blocksToRemove.ContainsKey($key)) {
                $skipIndentedBlock = $true
                continue
            }
        }

        $filtered.Add($line)
    }

    $prefix = @(
        "mixed-port: $Port",
        "bind-address: 127.0.0.1",
        "allow-lan: false",
        "external-controller: 127.0.0.1:9090",
        'secret: ""',
        "geo-auto-update: false",
        "geox-url:",
        '  geoip: "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/geoip.dat"',
        '  geosite: "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/geosite.dat"',
        '  asn: "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/GeoLite2-ASN.mmdb"'
    ) -join [Environment]::NewLine

    return $prefix + [Environment]::NewLine + [Environment]::NewLine +
        (($filtered -join [Environment]::NewLine).Trim()) + [Environment]::NewLine
}

function Write-DefaultConfig {
    param([string]$Path)
    @"
mixed-port: $MixedPort
bind-address: 127.0.0.1
allow-lan: false
mode: rule
log-level: info
ipv6: false

dns:
  enable: true
  listen: 127.0.0.1:$($MixedPort + 1000)
  enhanced-mode: fake-ip
  nameserver:
    - 223.5.5.5
    - 1.1.1.1

proxies: []

proxy-groups:
  - name: PROXY
    type: select
    proxies:
      - DIRECT

rules:
  - MATCH,DIRECT
"@ | Set-Content -LiteralPath $Path -Encoding UTF8
}

New-Item -ItemType Directory -Force -Path $runtimeDir | Out-Null

if ($Stop) {
    Stop-Mihomo
    return
}

& "$PSScriptRoot\download-mihomo-windows.ps1"
& "$PSScriptRoot\download-mihomo-geodata.ps1"

Copy-Item -LiteralPath "D:\Dev\Mihomo\geodata\geoip.dat" -Destination (Join-Path $runtimeDir "GeoIP.dat") -Force
Copy-Item -LiteralPath "D:\Dev\Mihomo\geodata\geosite.dat" -Destination (Join-Path $runtimeDir "GeoSite.dat") -Force
Copy-Item -LiteralPath "D:\Dev\Mihomo\geodata\GeoLite2-ASN.mmdb" -Destination (Join-Path $runtimeDir "ASN.mmdb") -Force

if ($SubscriptionUrl) {
    Write-Host "Downloading subscription config."
    $response = Invoke-WebRequest -Uri $SubscriptionUrl -Headers @{
        "User-Agent" = "CatchReport/0.1 mihomo-subscription"
    }
    Normalize-MihomoConfig -RawConfig $response.Content -Port $MixedPort |
        Set-Content -LiteralPath $configPath -Encoding UTF8
}
elseif ($ConfigFile) {
    if (-not (Test-Path -LiteralPath $ConfigFile)) {
        throw "Config file was not found: $ConfigFile"
    }
    Normalize-MihomoConfig -RawConfig (Get-Content -Raw -LiteralPath $ConfigFile) -Port $MixedPort |
        Set-Content -LiteralPath $configPath -Encoding UTF8
}
elseif (-not (Test-Path -LiteralPath $configPath)) {
    Write-DefaultConfig -Path $configPath
}

if (Test-Path -LiteralPath $pidPath) {
    $pidText = (Get-Content -Raw -LiteralPath $pidPath).Trim()
    $existing = if ($pidText) { Get-Process -Id ([int]$pidText) -ErrorAction SilentlyContinue } else { $null }
    if ($existing) {
        Write-Host "mihomo is already running on pid $($existing.Id)."
        return
    }
    Remove-Item -LiteralPath $pidPath -Force
}

if (Test-PortOpen -Port $MixedPort) {
    throw "127.0.0.1:$MixedPort is already in use. Stop the existing proxy or pass -MixedPort with another port."
}

$process = Start-Process `
    -FilePath $exe `
    -ArgumentList @("-d", $runtimeDir, "-f", $configPath) `
    -WorkingDirectory $runtimeDir `
    -RedirectStandardOutput $stdoutPath `
    -RedirectStandardError $stderrPath `
    -WindowStyle Hidden `
    -PassThru

Set-Content -LiteralPath $pidPath -Value $process.Id -Encoding ASCII

for ($i = 0; $i -lt 20; $i++) {
    if (Test-PortOpen -Port $MixedPort) {
        Write-Host "mihomo is listening at 127.0.0.1:$MixedPort. pid=$($process.Id)"
        return
    }
    Start-Sleep -Milliseconds 250
}

Write-Warning "mihomo started pid=$($process.Id), but 127.0.0.1:$MixedPort was not reachable yet. Check $stderrPath and $stdoutPath."
