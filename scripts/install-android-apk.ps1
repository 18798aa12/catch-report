$ErrorActionPreference = "Stop"
. "$PSScriptRoot\dev-env.ps1"

$repoRoot = Split-Path -Parent $PSScriptRoot
$apk = "$repoRoot\android\app\build\outputs\apk\debug\app-debug.apk"
$adb = "$env:ANDROID_HOME\platform-tools\adb.exe"

if (-not (Test-Path $apk)) {
    & "$PSScriptRoot\build-android.ps1"
}

& $adb wait-for-device
for ($i = 0; $i -lt 120; $i++) {
    $booted = (& $adb shell getprop sys.boot_completed 2>$null).Trim()
    if ($booted -eq "1") {
        break
    }
    Start-Sleep -Seconds 2
}

& $adb install -r $apk
