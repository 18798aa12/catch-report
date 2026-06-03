$ErrorActionPreference = "Stop"
. "$PSScriptRoot\dev-env.ps1"

$repoRoot = Split-Path -Parent $PSScriptRoot
$gradle = "D:\Dev\Gradle\gradle-9.5.1\bin\gradle.bat"

& "$PSScriptRoot\download-mihomo-cores.ps1"
& "$PSScriptRoot\download-mihomo-geodata.ps1"
& $gradle -p "$repoRoot\android" --no-daemon assembleDebug
