$env:JAVA_HOME = "D:\Dev\Java\jdk-21.0.11+10"
$env:ANDROID_HOME = "D:\Dev\Android\sdk"
$env:ANDROID_SDK_ROOT = "D:\Dev\Android\sdk"
$env:ANDROID_AVD_HOME = "D:\Dev\Android\avd"
$env:ANDROID_USER_HOME = "D:\Dev\Android\user-home"
$env:GRADLE_USER_HOME = "D:\Dev\Gradle\home"
$env:UV_CACHE_DIR = "D:\Dev\UV\cache"
$env:UV_TOOL_DIR = "D:\Dev\UV\tools"
$env:UV_PYTHON_INSTALL_DIR = "D:\Dev\Python"
$env:TEMP = "D:\Dev\Temp"
$env:TMP = "D:\Dev\Temp"
$env:GRADLE_OPTS = "-Djava.io.tmpdir=D:\Dev\Temp"

$paths = @(
    "$env:JAVA_HOME\bin",
    "D:\Dev\Gradle\gradle-9.5.1\bin",
    "$env:ANDROID_HOME\platform-tools",
    "$env:ANDROID_HOME\emulator",
    "$env:ANDROID_HOME\cmdline-tools\latest\bin",
    "D:\Dev\Tools\uv",
    "D:\Dev\VSCodeUser\bin"
)

$current = $env:PATH -split ";" | Where-Object { $_ }
$orderedPaths = $paths.Clone()
[array]::Reverse($orderedPaths)
foreach ($path in $orderedPaths) {
    if (-not ($current | Where-Object { $_.TrimEnd("\") -ieq $path.TrimEnd("\") })) {
        $env:PATH = "$path;$env:PATH"
    }
}

New-Item -ItemType Directory -Force -Path @(
    $env:ANDROID_AVD_HOME,
    $env:ANDROID_USER_HOME,
    $env:GRADLE_USER_HOME,
    $env:UV_CACHE_DIR,
    $env:UV_TOOL_DIR,
    $env:TEMP,
    "D:\Dev\Mihomo\cache",
    "D:\Dev\Mihomo\windows",
    "D:\Dev\Mihomo\windows\runtime",
    "D:\Dev\VSCode\data",
    "D:\Dev\VSCode\extensions",
    "D:\Dev\JetBrains\config",
    "D:\Dev\JetBrains\system",
    "D:\Dev\JetBrains\plugins",
    "D:\Dev\JetBrains\log"
) | Out-Null
