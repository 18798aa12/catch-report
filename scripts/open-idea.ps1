$ErrorActionPreference = "Stop"
. "$PSScriptRoot\dev-env.ps1"

$idea = "D:\Dev\JetBrains\IntelliJIDEA-Ultimate-2026.1.2\bin\idea64.exe"

Start-Process -FilePath $idea
