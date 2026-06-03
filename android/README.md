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

## 打开方式

用 Android Studio 打开 `android/` 目录。

当前开发机没有 Java、Gradle、Android SDK，所以本仓库先提交工程文件，构建验证需要在安装 Android Studio 后完成。
