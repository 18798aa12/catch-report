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
- 完整 TCP/UDP 用户态转发是下一阶段。
- 启动捕获 VPN 后，如果还没有接入转发引擎，普通联网可能中断。

后续代理链路线：

```text
App 流量 -> Catch Report VpnService -> PCAP 记录 -> 用户态转发 -> 自己的 SOCKS5/HTTP 代理 -> 出口
```

这可以满足“必须开 VPN/代理出口，同时抓包”的需求，并且仍然非 root。

当前代码已经支持在 UI 中选择“挂自己的代理再抓包”，并把代理配置写入服务和 `.pcap.json` 元数据。真正 TCP/UDP 转发引擎还未接入，所以现阶段该模式是工程预留，不代表已经能联网代理转发。

## Clash 推荐设置

如果使用 Android 本机 Clash，推荐让 Catch Report 填：

```text
代理地址：127.0.0.1
代理端口：Clash mixed-port，常见 7890
```

此时 Clash Android 不能开 VPN/TUN，只能作为本地代理服务。Catch Report 是唯一 VPN。

如果 Windows 上运行 Clash，推荐让 Android 端填 Windows 的局域网 IP 和 Clash mixed-port：

```text
代理地址：Windows 局域网 IP，例如 192.168.1.10
代理端口：Clash mixed-port，常见 7890
```

Windows Clash 需要开启 Allow LAN / `allow-lan: true`，并允许防火墙局域网入站。

## 打开方式

用 Android Studio 打开 `android/` 目录。

当前开发机没有 Java、Gradle、Android SDK，所以本仓库先提交工程文件，构建验证需要在安装 Android Studio 后完成。
