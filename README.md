# catch-report

Author: Zhou Qishun

Packet capture testing workspace for desktop and Android tools.

## 项目约束

- 安卓端必须使用非 root 模式。
- 安卓端抓包基于用户授权的 `VpnService`，不做隐藏抓包。
- 如果手机已经开着其他 VPN，优先通过电脑/路由器网关或桌面 VPN 虚拟网卡抓包，不要求手机 root。

## 已确定方案

- 电脑端第一阶段：Windows `pktmon` 抓包脚本 + 本地 PCAP/PCAPNG 查看器。
- 安卓端第一阶段：Kotlin `VpnService` 非 root 抓包，写出 raw IP PCAP。
- 代理链第二阶段：`Android App -> Catch Report VpnService -> PCAP 记录 -> 用户态转发 -> 自己的 SOCKS5/HTTP 代理 -> 出口`。
- Clash 优先路线：Android 只运行 Catch Report VPN，上游转发到 Windows Clash `mixed-port`，常见端口 `7890`。
- 如果 Clash Android 支持不开 VPN 的本地代理模式，也可以上游转发到 `127.0.0.1:7890`。

注意：安卓第一阶段先完成授权、前台服务、TUN 读取和 PCAP 写入；完整 TCP/UDP 转发和上游代理链是下一阶段。

## 目录

- [desktop](desktop): 电脑端工具。
- [android](android): 安卓非 root 抓包端。
- [docs](docs): 调研和架构决策。

## 调研参考

- [抓包软件近一年调研笔记（2025-06 至 2026-06）](docs/research-2026.md)
- [ADR-0001: 最终实现方案](docs/adr-0001-final-scheme.md)
- [ADR-0002: 上游代理转发路线](docs/adr-0002-upstream-proxy-forwarding.md)
- [ADR-0003: Clash 使用习惯下的抓包方案](docs/adr-0003-clash-integration.md)
