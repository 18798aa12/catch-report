$ErrorActionPreference = "Stop"
. "$PSScriptRoot\dev-env.ps1"

$repoRoot = Split-Path -Parent $PSScriptRoot
$cacheDir = "D:\Dev\Mihomo\geodata"
$assetDir = Join-Path $repoRoot "android\app\src\main\assets\mihomo-geodata"
$baseUrl = "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest"

$files = @(
    @{ Asset = "geoip.dat"; Target = "GeoIP.dat" },
    @{ Asset = "geosite.dat"; Target = "GeoSite.dat" },
    @{ Asset = "GeoLite2-ASN.mmdb"; Target = "ASN.mmdb" }
)

function Get-Sha256Hex {
    param([string]$Path)
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Get-ExpectedHash {
    param([string]$ShaFile)
    $content = Get-Content -Raw -LiteralPath $ShaFile
    return ($content -split "\s+")[0].ToLowerInvariant()
}

New-Item -ItemType Directory -Force -Path $cacheDir, $assetDir | Out-Null

foreach ($file in $files) {
    $asset = $file.Asset
    $downloadPath = Join-Path $cacheDir $asset
    $shaPath = Join-Path $cacheDir "$asset.sha256sum"
    $targetPath = Join-Path $assetDir $file.Target

    Write-Host "Checking geodata hash for $asset"
    Invoke-WebRequest -Uri "$baseUrl/$asset.sha256sum" -OutFile $shaPath
    $expectedHash = Get-ExpectedHash -ShaFile $shaPath

    $needsDownload = $true
    if (Test-Path -LiteralPath $downloadPath) {
        $needsDownload = (Get-Sha256Hex -Path $downloadPath) -ne $expectedHash
    }

    if ($needsDownload) {
        Write-Host "Downloading $asset"
        Invoke-WebRequest -Uri "$baseUrl/$asset" -OutFile $downloadPath
    }

    $actualHash = Get-Sha256Hex -Path $downloadPath
    if ($actualHash -ne $expectedHash) {
        throw "SHA256 mismatch for $asset. Expected $expectedHash, got $actualHash."
    }

    Copy-Item -LiteralPath $downloadPath -Destination $targetPath -Force
}

Write-Host "mihomo geodata assets are ready in $assetDir"
