# catch-report

Author: Zhou Qishun

Packet capture testing workspace for desktop and Android tools.

## 项目约束

- 安卓端必须使用非 root 模式。
- 安卓端抓包基于用户授权的 `VpnService`，不做隐藏抓包。
- 如果手机已经开着其他 VPN，优先通过电脑/路由器网关或桌面 VPN 虚拟网卡抓包，不要求手机 root。

## 调研参考

- [抓包软件近一年调研笔记（2025-06 至 2026-06）](docs/research-2026.md)
