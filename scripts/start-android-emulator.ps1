$ErrorActionPreference = "Stop"
. "$PSScriptRoot\dev-env.ps1"

$emulator = "$env:ANDROID_HOME\emulator\emulator.exe"

Start-Process -FilePath $emulator -ArgumentList @(
    "-avd", "Pixel6Api36",
    "-gpu", "swiftshader",
    "-no-snapshot",
    "-no-boot-anim"
) -WorkingDirectory "$env:ANDROID_HOME\emulator" -WindowStyle Hidden
