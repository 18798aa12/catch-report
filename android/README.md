# Android 非 root 抓包端

安卓端硬性要求：只做非 root。

第一阶段目标：

- 使用 `VpnService` 获取 Android 系统授权。
- 以可见前台服务运行。
- 从 TUN 读取原始 IP 包。
- 写出 PCAP 文件。
- 停止后把 PCAP 导入电脑端查看器。

第一阶段限制：

- 已实现的是捕获和 PCAP 写入骨架。
- `只记录 PCAP` 模式不会转发网络，启动后普通联网可能中断，但会写出 PCAP。
- `挂自己的代理再抓包` 模式已经接入 `hev-socks5-tunnel`，会把流量转到 SOCKS5/Clash。

代理链路线：

```text
App 流量 -> Catch Report VpnService -> hev-socks5-tunnel -> 127.0.0.1:7890 -> 内置 Mihomo -> 订阅节点/出口
```

这可以满足“必须开 VPN/代理出口，同时抓包”的需求，并且仍然非 root。

当前代码已经接入 `hev-socks5-tunnel` 作为 tun2socks 转发引擎，并内置官方 Mihomo core。代理模式会把 TUN fd 交给 native 引擎转发到本地 `127.0.0.1:7890`，再由 Mihomo 使用 Clash/Mihomo 配置或订阅节点出站，并在停止时写 `.pcap.pending.json` 元数据和 tun2socks 统计。

注意：代理模式下 TUN fd 由 tun2socks 独占读取，Kotlin 层不能同时读取同一个 fd 写 PCAP。完整“代理模式 PCAP 文件”需要下一步在 native HEV 读包位置加 packet tap。`只记录 PCAP` 模式仍会写 `.pcap` 文件。

## 内置 Mihomo 推荐流程

推荐优先使用内置 Mihomo，避免同时打开两个 Android VPN：

1. 打开 Catch Report。
2. 填写 Clash/Mihomo 订阅链接，点击“下载订阅配置”；也可以点击“导入 Clash/Mihomo 配置”导入本地 YAML。
3. 点击“使用内置 Mihomo 代理”。
4. 点击“开始抓包”，同意 Android VPN 授权。
5. 点击“刷新代理节点”。
6. 在第一个下拉框选择代理组，常见是“节点选择”。
7. 在第二个下拉框选择具体节点，点击“应用代理节点”。
8. 打开目标软件测试。
9. 回到 Catch Report 点击“停止抓包”。

导入或下载的配置会保存在 App 私有目录，运行前会强制本地监听：

```yaml
mixed-port: 7890
bind-address: 127.0.0.1
allow-lan: false
```

如果配置里自带 `tun:`，会被移除；Android 系统里仍然只有 Catch Report 一个 VPN。

## 外部 Clash 备选设置

如果使用 Android 本机 Clash，推荐让 Catch Report 填：

```text
代理地址：127.0.0.1
代理端口：Clash mixed-port，常见 7890；如果 mixed 不接 SOCKS5，改用 socks-port 7891
```

此时 Clash Android 不能开 VPN/TUN，只能作为本地代理服务。Catch Report 是唯一 VPN。

如果 Windows 上运行 Clash，推荐让 Android 端填 Windows 的局域网 IP 和 Clash mixed-port：

```text
代理地址：Windows 局域网 IP，例如 192.168.1.10
代理端口：Clash mixed-port，常见 7890；如果 mixed 不接 SOCKS5，改用 socks-port 7891
```

Windows Clash 需要开启 Allow LAN / `allow-lan: true`，并允许防火墙局域网入站。模拟器测试时也可以先执行：

```powershell
adb reverse tcp:7890 tcp:7890
```

然后在 Catch Report 中使用 `127.0.0.1:7890`。当前 HEV 引擎是 SOCKS5 上游，所以可用 Clash mixed-port 或 socks-port；HTTP-only 端口不适合这个链路。

## 已验证结果

- `只记录 PCAP`：Pixel6Api36 模拟器生成 `.pcap`，最终 smoke test 解析到 64 个包，元数据一致。
- `挂自己的代理再抓包`：`127.0.0.1:7891` 经 `adb reverse` 转到 Windows Clash，测试 HTTP 服务命中请求，`.pcap.pending.json` 写入 tun2socks 统计。
- `内置 Mihomo`：Pixel6Api36 模拟器中官方 x86_64 Mihomo core 启动成功，`hev-socks5-tunnel` 已将 VPN TCP 流量转入 `127.0.0.1:7890`。
- `节点选择`：已通过 Mihomo external-controller 读取代理组，并支持切换代理组里的具体节点。

## 打开方式

用 IntelliJ IDEA 打开 `android/` 目录。

本机 D 盘环境已经配置好：

```text
JDK: D:\Dev\Java\jdk-21.0.11+10
Android SDK: D:\Dev\Android\sdk
Gradle: D:\Dev\Gradle\gradle-9.5.1
Gradle cache: D:\Dev\Gradle\home
AVD: D:\Dev\Android\avd\Pixel6Api36.avd
```

命令行构建：

```powershell
.\scripts\build-android.ps1
```

启动模拟器并安装 APK：

```powershell
.\scripts\start-android-emulator.ps1
.\scripts\install-android-apk.ps1
```
