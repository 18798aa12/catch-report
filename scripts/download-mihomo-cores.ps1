$ErrorActionPreference = "Stop"
. "$PSScriptRoot\dev-env.ps1"

$repoRoot = Split-Path -Parent $PSScriptRoot
$cacheDir = "D:\Dev\Mihomo\cache"
$jniLibsDir = Join-Path $repoRoot "android\app\src\main\jniLibs"
$version = "v1.19.26"
$baseUrl = "https://github.com/MetaCubeX/mihomo/releases/download/$version"

$cores = @(
    @{
        Abi = "x86"
        Asset = "mihomo-android-386-$version.gz"
        Sha256 = "c25c7b9cfde18751fc2457bf6db84c5a6e5294a7ef0fa92fe06257ed471dc8c8"
    },
    @{
        Abi = "x86_64"
        Asset = "mihomo-android-amd64-$version.gz"
        Sha256 = "7d85546a7d536638306dc8cc1e054935f6dcdc6b18cd288d05a233219f427360"
    },
    @{
        Abi = "arm64-v8a"
        Asset = "mihomo-android-arm64-v8-$version.gz"
        Sha256 = "d191217c04bdbece126c12c5295007aa7509ae9df73d338ce1cc8ecfb07a33f6"
    },
    @{
        Abi = "armeabi-v7a"
        Asset = "mihomo-android-armv7-$version.gz"
        Sha256 = "27fcf4a9b574329cb9f8fe12227d69938f2dadf4441e39f20dd8bf135bb40c82"
    }
)

function Get-Sha256Hex {
    param([string]$Path)
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Expand-GzipFile {
    param(
        [string]$Source,
        [string]$Destination
    )

    $sourceStream = [System.IO.File]::OpenRead($Source)
    try {
        $gzipStream = [System.IO.Compression.GZipStream]::new($sourceStream, [System.IO.Compression.CompressionMode]::Decompress)
        try {
            $destinationStream = [System.IO.File]::Create($Destination)
            try {
                $gzipStream.CopyTo($destinationStream)
            } finally {
                $destinationStream.Dispose()
            }
        } finally {
            $gzipStream.Dispose()
        }
    } finally {
        $sourceStream.Dispose()
    }
}

New-Item -ItemType Directory -Force -Path $cacheDir, $jniLibsDir | Out-Null

foreach ($core in $cores) {
    $asset = $core.Asset
    $downloadPath = Join-Path $cacheDir $asset
    $targetDir = Join-Path $jniLibsDir $core.Abi
    $targetPath = Join-Path $targetDir "libmihomo.so"
    $url = "$baseUrl/$asset"

    if (-not (Test-Path -LiteralPath $downloadPath)) {
        Write-Host "Downloading $asset"
        Invoke-WebRequest -Uri $url -OutFile $downloadPath
    }

    $actualHash = Get-Sha256Hex -Path $downloadPath
    if ($actualHash -ne $core.Sha256) {
        throw "SHA256 mismatch for $asset. Expected $($core.Sha256), got $actualHash."
    }

    New-Item -ItemType Directory -Force -Path $targetDir | Out-Null
    if (-not (Test-Path -LiteralPath $targetPath)) {
        Write-Host "Expanding $asset -> $targetPath"
        Expand-GzipFile -Source $downloadPath -Destination $targetPath
    }
}

Write-Host "mihomo $version Android cores are ready in $jniLibsDir"
