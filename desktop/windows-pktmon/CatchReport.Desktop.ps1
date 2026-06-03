[CmdletBinding()]
param(
    [ValidateSet("start", "stop", "status", "filters", "clear-filters", "add-filter", "convert")]
    [string]$Action = "status",

    [ValidateSet("", "TCP", "UDP", "ICMP", "ICMPv6")]
    [string]$Protocol = "",

    [string]$Ip = "",
    [int]$Port = 0,
    [int]$PacketSize = 0,
    [string]$Name = "catch-report",
    [string]$InputFile = "",
    [string]$OutputFile = ""
)

$ErrorActionPreference = "Stop"

$RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$CaptureDir = Join-Path $RepoRoot "captures\desktop"
$ActiveFile = Join-Path $CaptureDir ".active-capture.json"

function Test-IsAdmin {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = [Security.Principal.WindowsPrincipal]::new($identity)
    return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Assert-Pktmon {
    if (-not (Get-Command pktmon -ErrorAction SilentlyContinue)) {
        throw "pktmon was not found. This tool requires Windows Packet Monitor."
    }
}

function Assert-Admin {
    if (-not (Test-IsAdmin)) {
        throw "This action requires an Administrator PowerShell session."
    }
}

function Ensure-CaptureDir {
    New-Item -ItemType Directory -Force -Path $CaptureDir | Out-Null
}

function Invoke-Pktmon {
    param([string[]]$Arguments)
    & pktmon @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "pktmon failed with exit code $LASTEXITCODE"
    }
}

function Add-CaptureFilter {
    param(
        [string]$FilterName,
        [string]$FilterProtocol,
        [string]$FilterIp,
        [int]$FilterPort
    )

    $filterArgs = @("filter", "add", $FilterName)
    if ($FilterProtocol) {
        $filterArgs += @("-t", $FilterProtocol)
    }
    if ($FilterIp) {
        $filterArgs += @("-i", $FilterIp)
    }
    if ($FilterPort -gt 0) {
        $filterArgs += @("-p", [string]$FilterPort)
    }

    if ($filterArgs.Count -le 3) {
        Write-Host "No filter fields were provided; leaving existing filters unchanged."
        return
    }

    Invoke-Pktmon -Arguments $filterArgs
}

function Start-Capture {
    Assert-Admin
    Ensure-CaptureDir

    if ($Protocol -or $Ip -or $Port -gt 0) {
        Invoke-Pktmon -Arguments @("filter", "remove")
        Add-CaptureFilter -FilterName $Name -FilterProtocol $Protocol -FilterIp $Ip -FilterPort $Port
    }

    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $etl = Join-Path $CaptureDir "catch-report-$stamp.etl"
    $pcapng = Join-Path $CaptureDir "catch-report-$stamp.pcapng"

    $metadata = [ordered]@{
        startedAt = (Get-Date).ToString("o")
        etl = $etl
        pcapng = $pcapng
        packetSize = $PacketSize
        protocol = $Protocol
        ip = $Ip
        port = $Port
    }
    $metadata | ConvertTo-Json | Set-Content -Encoding UTF8 -Path $ActiveFile

    Invoke-Pktmon -Arguments @(
        "start",
        "--capture",
        "--comp", "nics",
        "--pkt-size", [string]$PacketSize,
        "--file-name", $etl
    )

    Write-Host "Capture started."
    Write-Host "ETL: $etl"
}

function Stop-Capture {
    Assert-Admin
    Ensure-CaptureDir

    Invoke-Pktmon -Arguments @("stop")

    if (-not (Test-Path $ActiveFile)) {
        Write-Host "No active capture metadata was found. Stop completed."
        return
    }

    $metadata = Get-Content -Raw -Path $ActiveFile | ConvertFrom-Json
    if (Test-Path $metadata.etl) {
        Invoke-Pktmon -Arguments @("etl2pcap", $metadata.etl, "--out", $metadata.pcapng)
        Write-Host "PCAPNG: $($metadata.pcapng)"
    }
    else {
        Write-Warning "ETL file was not found: $($metadata.etl)"
    }

    Remove-Item -Force -Path $ActiveFile
}

function Convert-Capture {
    Assert-Pktmon
    Ensure-CaptureDir

    if (-not $InputFile) {
        throw "-InputFile is required for convert."
    }
    if (-not (Test-Path $InputFile)) {
        throw "Input file was not found: $InputFile"
    }

    $out = $OutputFile
    if (-not $out) {
        $out = [System.IO.Path]::ChangeExtension($InputFile, ".pcapng")
    }

    Invoke-Pktmon -Arguments @("etl2pcap", $InputFile, "--out", $out)
    Write-Host "PCAPNG: $out"
}

Assert-Pktmon

switch ($Action) {
    "start" {
        Start-Capture
    }
    "stop" {
        Stop-Capture
    }
    "status" {
        & pktmon status
        if ($LASTEXITCODE -ne 0) {
            Write-Warning "pktmon status failed. Run PowerShell as Administrator for live status."
        }
    }
    "filters" {
        & pktmon filter list
        if ($LASTEXITCODE -ne 0) {
            Write-Warning "pktmon filter list failed. Run PowerShell as Administrator."
        }
    }
    "clear-filters" {
        Assert-Admin
        Invoke-Pktmon -Arguments @("filter", "remove")
    }
    "add-filter" {
        Assert-Admin
        Add-CaptureFilter -FilterName $Name -FilterProtocol $Protocol -FilterIp $Ip -FilterPort $Port
    }
    "convert" {
        Convert-Capture
    }
}
