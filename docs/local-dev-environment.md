# 本地开发环境

本机环境尽量固定在 D 盘，避免 C 盘缓存膨胀。

## 已安装路径

- JDK 21: `D:\Dev\Java\jdk-21.0.11+10`
- IntelliJ IDEA Ultimate 2026.1.2: `D:\Dev\JetBrains\IntelliJIDEA-Ultimate-2026.1.2`
- VS Code 1.122.1: `D:\Dev\VSCodeUser`
- Android SDK: `D:\Dev\Android\sdk`
- Android AVD: `D:\Dev\Android\avd\Pixel6Api36.avd`
- Gradle 9.5.1: `D:\Dev\Gradle\gradle-9.5.1`
- Gradle cache: `D:\Dev\Gradle\home`
- uv: `D:\Dev\Tools\uv\uv.exe`
- uv cache/tools/Python: `D:\Dev\UV`, `D:\Dev\Python`
- Temp/cache hygiene: `TEMP` / `TMP` / Gradle `java.io.tmpdir` point to `D:\Dev\Temp` when using `scripts\dev-env.ps1`

桌面快捷方式使用通用名称：`IntelliJ IDEA Ultimate`、`Visual Studio Code`、`Android Emulator`。其中 `Android Emulator` 指向 `D:\Dev\Android\scripts\start-emulator-hidden.vbs`，用于隐藏 emulator/netsimd 控制台窗口。

## 常用命令

```powershell
.\scripts\build-android.ps1
.\scripts\start-android-emulator.ps1
.\scripts\install-android-apk.ps1
.\scripts\open-idea.ps1
.\scripts\open-vscode.ps1
```

IDEA 的 config/system/plugins/log 已在 `idea.properties` 中改到 `D:\Dev\JetBrains`。
VS Code 快捷方式会使用 `D:\Dev\VSCode\data` 和 `D:\Dev\VSCode\extensions`。

## Android 项目

用 IDEA 打开 `android/` 目录。第一次打开时选择 D 盘 JDK 和 Android SDK：

```text
JDK: D:\Dev\Java\jdk-21.0.11+10
Android SDK: D:\Dev\Android\sdk
Gradle user home: D:\Dev\Gradle\home
```

JetBrains 教育优惠需要在 IDEA 内由本人登录账号激活。

## 已验证

- Android Debug APK 构建和安装成功。
- Pixel6Api36 模拟器可启动并运行 Catch Report。
- Windows `pktmon` 管理员抓包测试已生成 `captures\desktop\catch-report-20260603-211226.etl` 和 `.pcapng`。
