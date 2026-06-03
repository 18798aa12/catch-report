# ADR-0001: 最终实现方案

日期：2026-06-03

## 状态

已确定，开始执行。

## 目标

做两个软件：

- 电脑端抓包和分析工具。
- 安卓端非 root 抓包工具。

项目只面向自有设备、自有网络和授权测试。安卓端必须非 root，不依赖 Magisk、不修改系统分区、不做隐藏抓包。

## 决策

### 电脑端第一阶段

先做 Windows 可用版本：

1. 抓包后端使用 Windows 自带 `pktmon`。
2. 脚本负责开始、停止、过滤、转换 PCAPNG。
3. 用静态网页做 PCAP/PCAPNG 查看器，不依赖 Node、Python、Rust 或安装包。

选择原因：

- 当前开发机没有 Rust、Java、Gradle、Android SDK，直接做完整 Tauri/Rust 会卡工具链。
- Windows 已有 `pktmon`，可以先完成可用抓包闭环。
- PCAP/PCAPNG 查看器可以马上用于分析安卓端导出的文件。

第二阶段再接 Npcap/libpcap，实现实时包列表、网卡选择、BPF 过滤和更完整协议解析。

### 安卓端第一阶段

使用 Kotlin + Android `VpnService`。

第一版能力：

1. 用户点击开始。
2. Android 系统弹出 VPN 授权。
3. App 以非 root 前台服务运行。
4. 读取 TUN 原始 IP 包。
5. 写出 PCAP 文件。
6. 用户停止后，可把 PCAP 导入电脑端查看器。

重要限制：

- 第一阶段先实现捕获和 PCAP 写入，不实现完整 TCP/UDP 用户态转发。
- 启动捕获 VPN 后，普通联网可能会被中断，这是 MVP 的已知限制。
- 第二阶段实现用户态转发或“抓包 VPN -> 上游代理”的组合模式。

### 开着其他 VPN 的处理

安卓系统通常只允许一个活跃 VPN。非 root 模式下，如果手机已经开着其他 VPN，本项目不尝试绕过限制。

推荐路线：

1. 手机连到已经走 VPN 的电脑/路由器网关，在电脑/路由器抓。
2. VPN 开在电脑上，电脑端选择物理网卡或 VPN 虚拟网卡抓。
3. 后续做“安卓抓包 VPN + 上游 SOCKS/HTTP 代理”组合模式。

### 必须走代理/VPN 出口时

可以做，但架构必须是“一个 VPN + 上游代理”，不是两个 Android VPN 同时运行。

链路：

```text
Android App 流量
  -> Catch Report VpnService
  -> 记录 PCAP / 生成流统计
  -> 用户态 TCP/UDP 转发
  -> 自己的 SOCKS5 / HTTP CONNECT / 网关代理
  -> 代理或 VPN 出口
```

这个方案仍然是非 root，因为 Android 系统只看到一个 VPN：Catch Report。上游代理只是 App 内部转发目标。

第一版只预留配置接口；第二阶段实现 SOCKS5/HTTP CONNECT 转发；第三阶段再考虑 UDP/QUIC 的代理支持。

## 不做

- 不做 root 抓包。
- 不做隐藏抓包。
- 不做证书绑定绕过。
- 不自动上传流量。
- 不提取账号密码。
- 不实现隐蔽驻留。

## 目录规划

- `desktop/windows-pktmon/`: Windows 抓包脚本。
- `desktop/pcap-viewer/`: 本地 PCAP/PCAPNG 查看器。
- `android/`: Android 非 root `VpnService` App。
- `docs/`: 调研、方案和开发记录。
- `captures/`: 本地抓包输出目录，默认不提交。
