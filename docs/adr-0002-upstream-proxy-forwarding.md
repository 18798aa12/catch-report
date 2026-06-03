# ADR-0002: 上游代理转发路线

日期：2026-06-03

## 状态

已确定接口，转发引擎待接入。

## 问题

用户希望在必须经过自己的代理或 VPN 出口时仍然抓包。安卓非 root 环境不能同时运行两个 VPN App，所以不能使用：

```text
其他 VPN App + Catch Report VPN
```

## 决策

使用一个 VPN，加一个上游代理。

```text
App 流量
  -> Catch Report VpnService
  -> PCAP 记录 / 流统计
  -> 用户态转发引擎
  -> SOCKS5 或 HTTP CONNECT
  -> 用户自己的代理/VPN 出口
```

Android 系统层面只看到一个 VPN，也就是 Catch Report 的 `VpnService`。上游代理是 App 内部主动连接出去的目标。

## 当前实现

已完成：

- `CaptureMode`: `CAPTURE_ONLY` / `UPSTREAM_PROXY`。
- `CaptureConfig`: 从 UI 传到 `CaptureVpnService`。
- `UpstreamProxyConfig`: 保存 SOCKS5/HTTP CONNECT 代理参数。
- `PacketForwarder`: 转发引擎接口。
- `CaptureOnlyForwarder`: 只记录 PCAP。
- `UpstreamProxyForwarder`: 代理转发占位实现，记录配置和待转发状态。
- `CaptureMetadataWriter`: 停止抓包时写 `.pcap.json` 元数据。

未完成：

- TCP 状态机。
- UDP/QUIC 转发。
- SOCKS5 握手和 UDP ASSOCIATE。
- HTTP CONNECT 转发。
- 把上游连接用 `VpnService.protect(socket)` 排除在 VPN 外，避免回环。

## 下一步

优先接成熟的 tun2socks/用户态网络栈，而不是手写完整 TCP。

候选方向：

- Android native tun2socks library。
- HevSocks5Tunnel 类路线。
- sing-box/tun2socks 类路线。
- 自研最小 TCP 栈只作为最后选择。

评估标准：

- 许可证能接受。
- Android 非 root 可用。
- 能接 SOCKS5，最好支持 HTTP CONNECT。
- 能处理 UDP 或至少明确 UDP 限制。
- 能在所有上游 socket 调用 `VpnService.protect`。

用户常用 Clash 时，优先把上游目标设计为 Windows Clash 的 `mixed-port`。这比在 Android 上同时运行 Clash VPN 和 Catch Report VPN 更符合非 root 限制。

## 风险

- 手写 TCP 栈复杂度高，容易造成断流、重传错误、性能问题。
- UDP/QUIC 代理比 TCP 更麻烦，很多 HTTPS/3 流量会走 QUIC。
- 代理链会增加延迟，需要 UI 明确显示当前模式和限制。
