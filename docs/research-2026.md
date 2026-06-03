# 抓包软件近一年调研笔记（2025-06 至 2026-06）

本项目用于自有设备、自有网络、授权测试和协议分析。设计原则：明确授权、前台可见、本地优先、不隐蔽抓取、不自动上传、不采集账号密码、不绕过证书绑定。

硬性要求：安卓端只做非 root 模式。任何需要 root、Magisk、系统证书写入、系统分区改动或内核级抓包的方案，都不作为本项目实现路线。

## 调研来源

- Android `VpnService`: https://developer.android.com/reference/android/net/VpnService
- Android VPN 开发指南: https://developer.android.com/develop/connectivity/vpn
- Android 前台服务类型: https://developer.android.com/develop/background-work/services/fgs/service-types
- Android Network Security Config: https://developer.android.com/privacy-and-security/security-config
- PCAPdroid 项目和 API: https://github.com/emanuele-f/PCAPdroid
- PCAPdroid App API: https://github.com/emanuele-f/PCAPdroid/blob/master/docs/app_api.md
- Npcap: https://npcap.com/
- Wireshark Release Notes: https://www.wireshark.org/docs/relnotes/
- Microsoft Packet Monitor: https://learn.microsoft.com/en-us/windows-server/networking/technologies/pktmon/pktmon
- WinDivert: https://reqrypt.org/windivert.html
- mitmproxy 模式和证书: https://docs.mitmproxy.org/stable/concepts/modes/ 和 https://docs.mitmproxy.org/stable/concepts/certificates/
- Scapy: https://pypi.org/project/scapy/
- Rust pcap crate: https://github.com/rust-pcap/pcap
- PARROT Android 流量采集论文（2025-09-11）: https://arxiv.org/abs/2509.09537
- MVPNalyzer Android VPN 审计论文（NDSS 2026）: https://www.ndss-symposium.org/ndss-paper/mvpnalyzer-an-investigative-framework-for-auditing-the-security-privacy-of-mobile-vpns/

## 总体结论

1. 安卓端必须走非 root 的 `VpnService` 路线。

   抓包 App 创建一个本地 VPN/TUN 虚拟网卡，Android 系统把流量导入这个虚拟网卡，App 从文件描述符读取 IP 包，记录为 PCAP/PCAPNG，再负责转发流量。PCAPdroid 这类成熟工具也是这个方向。本项目不要求、不提示、不依赖 root 权限。

2. 安卓抓包必须前台可见并且用户授权。

   Android 8+ 要求 VPN 服务启动后进入前台服务，否则系统会停止服务。Android 14/15 对前台服务类型也更严格，VPN 类应用应清晰声明用途，保留常驻通知、开始/停止按钮、导出路径和隐私说明。

3. 安卓同时只能有一个活跃 VPN，这是“开着 VPN 抓包”的核心限制。

   如果抓包软件自己通过 `VpnService` 开 VPN，就可以抓；但如果手机已经开了 WireGuard、OpenVPN、Clash、v2rayNG 等另一个 VPN，再启动我们的抓包 VPN，通常会替换或断开原来的 VPN。因为本项目坚持非 root，不能依赖系统接口去绕过这个限制。

4. HTTPS 内容解密不适合作为默认功能。

   近一年移动 App 流量更偏 TLS 1.3、QUIC、DoT/DoH、证书绑定。Android 7+ 之后，目标 App 默认不信任用户安装的 CA，除非它自己在 Network Security Config 里允许。因此默认只抓元数据和包：IP、端口、协议、DNS、SNI/ALPN（能看到时）、时间、流量大小、PCAP 文件。HTTPS 解密只做可选实验室模式，用于自己的 debug App 或模拟器，不做 pinning 绕过。

5. 桌面端 Windows 优先用 Npcap/libpcap。

   Npcap 是目前 Windows 抓包最稳的底层库，支持 Windows 10/11，提供通用 Pcap API，可抓网卡、回环、部分 VPN 相关流量。桌面端 MVP 应先基于 Npcap/libpcap 做接口选择、BPF 过滤、包列表、流统计和 PCAP 保存。

6. 桌面端备用方案：Pktmon 和 WinDivert。

   `pktmon` 是 Windows 内置诊断工具，可转 PCAPNG，适合作为导入/辅助诊断功能。WinDivert 可以捕获、丢弃、修改、重注入数据包，适合后续高级拦截版，但驱动和管理员权限复杂度更高，不适合第一版。

7. 文件格式优先 PCAP，后续加 PCAPNG。

   PCAP 简单、兼容 Wireshark。PCAPNG 更适合后续保存接口信息、App 包名、进程名、注释、扩展元数据。

## 开着 VPN 怎么抓包

这里要分清两种情况。

### 情况 A：抓包软件自己开 VPN

这是安卓无 root 抓包的常规方法。流程是：

1. 用户打开我们的安卓抓包 App。
2. 用户点击开始抓包。
3. Android 弹出 VPN 授权提示。
4. 用户同意后，我们的 `VpnService` 成为当前 VPN。
5. App 读取 TUN 流量，写入 PCAP，并转发网络请求。

这就是第一版安卓 App 的推荐路线。

### 情况 B：手机已经开着别的 VPN

这种情况下我们的安卓 `VpnService` 不能再同时接管网络。可选方案如下：

1. 在手机外面抓。

   让手机连到一个已经走 VPN 的 Wi-Fi 路由器或电脑网关，然后在路由器/电脑上抓包。这样手机仍然“经过 VPN”，抓包点在外部网关。

2. 在电脑 VPN 网卡上抓。

   如果 VPN 开在电脑上，桌面端用 Npcap 选择不同网卡：
   - 抓物理网卡：看到的是电脑到 VPN 服务器的加密隧道。
   - 抓 VPN 虚拟网卡：有机会看到 VPN 内层流量，取决于 VPN 驱动是否暴露虚拟接口。

3. 不采用 root 安卓设备抓包。

   root 后虽然能从系统接口抓包，但这不符合本项目约束。遇到“手机已开其他 VPN”的场景，改用外部网关、电脑 VPN 网卡、代理链或后续的“抓包 + 上游代理/VPN”组合模式。

4. 做成一个“抓包 + 上游代理/VPN”的组合 App。

   也就是我们的 App 自己作为唯一 VPN，同时把流量转发到 SOCKS5、HTTP 代理或某种上游隧道。这样不是两个 VPN 同时跑，而是一个 VPN 内部再转发。这个方案可行，但比 MVP 大很多。

5. 对支持代理的测试 App，用代理链。

   如果目标 App 能配置 HTTP/SOCKS 代理，可以让 App 走代理，代理再走 VPN。这适合自己的测试 App，不适合所有第三方 App。

### 必须挂自己的代理再抓包

可以做，推荐作为第二阶段重点。

非 root 链路应设计为：

```text
Android App 流量
  -> Catch Report VpnService
  -> PCAP 记录和流统计
  -> 用户态 TCP/UDP 转发
  -> 自己的 SOCKS5 / HTTP CONNECT 代理
  -> 代理或 VPN 出口
```

这样 Android 系统层面仍然只有一个 VPN，也就是 Catch Report 自己的 `VpnService`。上游代理只是 App 内部转发目标，不违反“安卓只能一个活跃 VPN”的限制。

第一版先预留代理配置；第二版实现 SOCKS5/HTTP CONNECT；第三版再处理 UDP/QUIC 的代理能力。

## 推荐架构

做两个 App，再加一套共享的抓包格式约定。

### 电脑端

- 技术：Rust core + Tauri UI。
- 抓包后端：Rust `pcap` crate，Windows 依赖 Npcap，Linux/macOS 依赖 libpcap。
- 第一版功能：
  - 网卡列表
  - 开始/停止抓包
  - BPF 过滤输入
  - 实时包列表
  - 五元组流统计
  - 保存 PCAP
  - 打开 PCAP
  - 导入安卓端导出的 PCAP
- 第一版解析：
  - Ethernet
  - IPv4 / IPv6
  - TCP / UDP / ICMP
  - DNS 摘要
  - payload 十六进制/ASCII 预览，默认截断

### 安卓端

- 技术：Kotlin + Android `VpnService` + 前台通知 + 最小原生 Android UI。后续可升级到 Jetpack Compose。
- 权限路线：全程非 root，不依赖 Magisk、不写系统证书、不修改系统分区。
- 第一版功能：
  - 点击开始
  - 系统 VPN 授权
  - 前台服务运行
  - 写 PCAP 文件
  - 显示包数、字节数、当前 App/流统计
  - 停止并导出 PCAP
- App 过滤：
  - 读取已安装 App 列表
  - 在 `establish()` 之前配置 `addAllowedApplication`
- HTTPS：
  - 默认只做元数据和 PCAP
  - 后续可加 mitmproxy 实验室模式，用于自己的 App 或模拟器

### 共享格式

- 第一版使用 PCAP。
- 另存一个 JSON sidecar，记录：
  - capture id
  - 设备名
  - App 包名或进程名
  - 开始/结束时间
  - 过滤条件
  - 包数和字节数

## 开发顺序

1. 建仓库结构：`desktop/`、`android/`、`docs/`、`samples/`。
2. 先做电脑端 MVP，因为它能先打开和分析 PCAP。
3. 再做安卓端 MVP，先实现授权 VPN 抓包和 PCAP 导出。
4. 打通安卓导出 PCAP，电脑端导入分析。
5. 后续再加 Pktmon 导入、PCAPNG、mitmproxy 实验室模式、上游 SOCKS5/HTTP CONNECT 代理模式。

## 不做的事情

- 不做隐藏后台抓包。
- 不抓别人设备或未授权网络。
- 不自动提取账号密码。
- 不绕过证书绑定。
- 不做隐蔽驻留。
- 不默认上传流量。
