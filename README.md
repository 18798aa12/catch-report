# catch-report

Author: Zhou Qishun

Packet capture testing workspace for desktop and Android tools.

## 项目约束

- 安卓端必须使用非 root 模式。
- 安卓端抓包基于用户授权的 `VpnService`，不做隐藏抓包。
- 如果手机已经开着其他 VPN，优先通过电脑/路由器网关或桌面 VPN 虚拟网卡抓包，不要求手机 root。

## 已确定方案

- 电脑端第一阶段：Windows `pktmon` 抓包脚本 + 本地 PCAP/PCAPNG 查看器；可用 `scripts/start-windows-mihomo.ps1` 启动本机 Mihomo 代理出口。
- 安卓端第一阶段：Kotlin `VpnService` 非 root 抓包，`只记录 PCAP` 模式写出 raw IP PCAP。
- 发布产物：`dist/CatchReport-Android-debug.apk` 和 `dist/CatchReport-Windows.exe`。
- 代理链主线：`Android App -> Catch Report VpnService -> hev-socks5-tunnel -> 127.0.0.1:7890 -> 内置 Mihomo -> 订阅节点/出口`。
- Clash 兼容路线：仍可上游转发到外部 Clash mixed 入口，常见端口 `7890`；如果客户端 mixed 不接 SOCKS5，再改填 socks-port `7891`。
- Android 端可以导入 Clash/Mihomo YAML，也可以填写订阅 URL 下载到 App 私有配置目录；订阅不提交 GitHub，也不写入抓包元数据。
- Android 端可以在抓包启动后通过“刷新代理节点/应用代理节点”切换 Mihomo 代理组里的具体节点。
- Android 端使用分步向导：先选“不挂代理抓包”或“挂代理抓包”，未完成上一步时不能继续下一步。
- Android 端内置抓包结果预览，结果页支持按协议、IP、端口、DNS 域名和字段值搜索、分页展示、点击单包查看字段 / Value、IP/TCP/UDP/DNS、HTTP 明文字段、TLS SNI/ALPN 和原始 HEX，并可在只记录 PCAP 模式边抓边刷新预览。
- Android 端支持删除选中的抓包文件，或一键清空历史抓包结果。

注意：代理模式已经接入 `hev-socks5-tunnel` 转发到 SOCKS5/Clash；该模式下 TUN fd 由 native 引擎独占读取，当前写 `.pcap.pending.json` 元数据和转发统计。完整“边转发边写 PCAP”需要下一步在 native 读包位置加 packet tap。

## 本机测试记录

- Android `只记录 PCAP`：已在 Pixel6Api36 模拟器生成合法 PCAP，并在应用内预览解析到 214 个包。
- Android `应用内搜索/详情`：已验证搜索 `example.com` 命中 DNS query，点击详情项可展示 IPv4、UDP、DNS、Payload ASCII 和 HEX。
- Android `挂自己的代理再抓包`：已验证 `Catch Report VPN -> hev-socks5-tunnel -> 127.0.0.1:7891 -> Clash -> HTTP 测试服务`，服务器命中测试请求。
- Android `内置 Mihomo`：已验证内置官方 Mihomo core 启动并监听 `127.0.0.1:7890`，VPN 流量可经 HEV 转入 core。
- Windows `pktmon`：已用管理员模式生成 ETL 和 PCAPNG，转换结果包含 12620 个格式化包。
- Windows `EXE`：`dist/CatchReport-Windows.exe` 已生成，内置管理员启动 manifest，可启动/停止 pktmon、打开抓包目录和本地查看器。

## 目录

- [desktop](desktop): 电脑端工具。
- [android](android): 安卓非 root 抓包端。
- [scripts](scripts): 本机 D 盘开发环境、构建、模拟器启动脚本。
- [docs](docs): 调研和架构决策。

## 调研参考

- [抓包软件近一年调研笔记（2025-06 至 2026-06）](docs/research-2026.md)
- [ADR-0001: 最终实现方案](docs/adr-0001-final-scheme.md)
- [ADR-0002: 上游代理转发路线](docs/adr-0002-upstream-proxy-forwarding.md)
- [ADR-0003: Clash 使用习惯下的抓包方案](docs/adr-0003-clash-integration.md)
- [ADR-0004: 内置 Mihomo 完整代理核心](docs/adr-0004-embedded-mihomo-core.md)
- [本地开发环境](docs/local-dev-environment.md)
