$ErrorActionPreference = "Stop"
. "$PSScriptRoot\dev-env.ps1"

$version = "v1.19.26"
$asset = "mihomo-windows-amd64-$version.zip"
$sha256 = "955c689499fa8b5d2378423929bc8335c9a666bc1556a871737d67e233fa2261"
$baseUrl = "https://github.com/MetaCubeX/mihomo/releases/download/$version"
$cacheDir = "D:\Dev\Mihomo\cache"
$installDir = "D:\Dev\Mihomo\windows"
$downloadPath = Join-Path $cacheDir $asset
$extractDir = Join-Path $cacheDir "mihomo-windows-amd64-$version"
$targetPath = Join-Path $installDir "mihomo.exe"

function Get-Sha256Hex {
    param([string]$Path)
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

New-Item -ItemType Directory -Force -Path $cacheDir, $installDir | Out-Null

if (-not (Test-Path -LiteralPath $downloadPath)) {
    Write-Host "Downloading $asset"
    Invoke-WebRequest -Uri "$baseUrl/$asset" -OutFile $downloadPath
}

$actualHash = Get-Sha256Hex -Path $downloadPath
if ($actualHash -ne $sha256) {
    throw "SHA256 mismatch for $asset. Expected $sha256, got $actualHash."
}

if (-not (Test-Path -LiteralPath $targetPath)) {
    if (Test-Path -LiteralPath $extractDir) {
        Remove-Item -LiteralPath $extractDir -Recurse -Force
    }
    New-Item -ItemType Directory -Force -Path $extractDir | Out-Null
    Expand-Archive -LiteralPath $downloadPath -DestinationPath $extractDir -Force

    $exe = Get-ChildItem -LiteralPath $extractDir -Recurse -File -Filter "*.exe" |
        Select-Object -First 1
    if (-not $exe) {
        throw "No mihomo executable was found in $asset."
    }
    Copy-Item -LiteralPath $exe.FullName -Destination $targetPath -Force
}

Write-Host "mihomo $version Windows core is ready: $targetPath"
