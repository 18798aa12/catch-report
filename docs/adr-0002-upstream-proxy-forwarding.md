# ADR-0002: 上游代理转发路线

日期：2026-06-03

## 状态

已接入 SOCKS5 tun2socks 转发引擎，并接入内置 Mihomo 代理核心。

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
  -> 内置 Mihomo 或外部 SOCKS5/mixed 代理
  -> 用户自己的代理/VPN 出口
```

Android 系统层面只看到一个 VPN，也就是 Catch Report 的 `VpnService`。上游代理是 App 内部主动连接出去的目标。

## 当前实现

已完成：

- `CaptureMode`: `CAPTURE_ONLY` / `UPSTREAM_PROXY`。
- `CaptureConfig`: 从 UI 传到 `CaptureVpnService`。
- `UpstreamProxyConfig`: 保存 SOCKS5/HTTP CONNECT 代理参数。
- `CaptureOnlyForwarder`: 只记录 PCAP。
- `CaptureMetadataWriter`: 停止抓包时写 `.pcap.json` 元数据。
- `hev-socks5-tunnel`: 代理模式下接管 TUN fd，转发到 SOCKS5/Clash。
- `MihomoCore`: 启动 Android 官方 mihomo core，监听 `127.0.0.1:7890`。
- 订阅下载：UI 可下载 Clash/Mihomo 订阅到 App 私有配置目录。
- 配置规范化：强制本地 mixed-port，并移除顶层 `tun:`，避免第二个 Android VPN。
- HEV mapdns：代理模式 DNS 使用 `198.18.0.2`，避免普通 DNS 直连。
- Android `INTERNET` 权限：native socks socket 可正常创建。

未完成：

- HTTP CONNECT 转发。
- 代理模式下的完整 PCAP 文件；当前 native 引擎独占读取 TUN fd，只写 `.pcap.pending.json` 和统计。
- UI 连通性测试。

## 下一步

下一步优先在 HEV TUN 读包位置加 packet tap，让代理模式边转发边写 PCAP。HTTP CONNECT 可作为后续上游类型，覆盖只开放 HTTP 入口的 Clash 配置。

用户常用 Clash 配置时，优先使用内置 Mihomo 直接加载 Clash/Mihomo YAML 或订阅。外部 Clash 仍作为备选：上游目标填 Clash `mixed-port`，常见 `7890`；如果客户端 mixed 不接 SOCKS5，再改填 `socks-port = 7891`。

## 风险

- 手写 TCP 栈复杂度高，容易造成断流、重传错误、性能问题。
- UDP/QUIC 代理比 TCP 更麻烦，很多 HTTPS/3 流量会走 QUIC。
- 代理链会增加延迟，需要 UI 明确显示当前模式和限制。
