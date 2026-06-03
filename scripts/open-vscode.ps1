$ErrorActionPreference = "Stop"
. "$PSScriptRoot\dev-env.ps1"

$code = "D:\Dev\VSCodeUser\Code.exe"

Start-Process -FilePath $code -ArgumentList @(
    "--user-data-dir", "D:\Dev\VSCode\data",
    "--extensions-dir", "D:\Dev\VSCode\extensions"
)
