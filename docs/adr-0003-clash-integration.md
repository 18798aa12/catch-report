# ADR-0003: Clash 使用习惯下的抓包方案

日期：2026-06-03

## 状态

已确定为优先兼容路线。

## 背景

用户在 Android 和 Windows 上通常都使用 Clash 进行代理。Clash/mihomo 类核心常见入站端口包括：

- `port`: HTTP(S) 代理端口，常见 `7890`。
- `socks-port`: SOCKS4/4a/5 代理端口，常见 `7891`。
- `mixed-port`: 混合端口，一个 TCP 端口同时支持 HTTP(S) 和 SOCKS5，很多配置常见 `7890`，部分客户端默认可能不同。

本项目默认按 Clash `mixed-port = 7890` 预填，但用户应以自己 Clash 客户端的实际运行配置为准。

## Windows 使用 Clash

Windows 上使用 Clash 时有两种常见模式。

### 系统代理模式

Clash 只作为本机 HTTP/SOCKS 代理。电脑端抓包时：

- 抓物理网卡：看到本机到代理出口的连接，以及 App 到本地 Clash 端口的流量视抓包点而定。
- 后续接 Npcap 后，可补 loopback 抓包，专门看 `127.0.0.1:7890` / `127.0.0.1:7891`。

### TUN 模式

Clash 在 Windows 上创建虚拟网卡。电脑端抓包时：

- 抓物理网卡：通常看到加密后的外层连接。
- 抓 Clash/TUN 虚拟网卡：更可能看到内层流量，取决于驱动暴露方式。

第一阶段 `pktmon` 先做基础抓包，第二阶段 Npcap 做更细网卡选择。

## Android 使用 Clash

Android 上如果 Clash 正在以 VPN/TUN 模式运行，它会占用 Android 唯一活跃 VPN。Catch Report 也是 `VpnService`，所以两者不能同时作为 VPN 运行。

因此不推荐：

```text
Android Clash VPN + Catch Report VPN
```

推荐：

```text
Android App 流量
  -> Catch Report VpnService
  -> PCAP 记录 / 流统计
  -> 用户态转发引擎
  -> Windows Clash mixed-port
  -> Clash 代理出口
```

这样 Android 只运行 Catch Report 一个 VPN；Clash 运行在 Windows 上，作为 LAN 上游代理。

## Windows Clash 需要打开 LAN 入站

为了让 Android 连接 Windows Clash，需要在 Windows Clash 客户端中确认：

1. Clash 正在监听 mixed-port，例如 `7890`。
2. 开启 `allow-lan` 或 GUI 中的 Allow LAN / 允许局域网连接。
3. `bind-address` 允许局域网访问，例如 `*`、`0.0.0.0` 或 Windows 的局域网 IP。
4. Windows 防火墙允许该 TCP 端口的局域网入站。
5. Android 和 Windows 在同一个可信 Wi-Fi/LAN，且路由器没有开启客户端隔离。

示例配置：

```yaml
mixed-port: 7890
allow-lan: true
bind-address: '*'
```

安全提醒：`allow-lan` 不是认证。只应在可信局域网打开，必要时用防火墙限制来源 IP。

## 当前项目落地

- Android UI 的上游代理提示已改为 Clash mixed-port。
- `UpstreamProxyConfig.DEFAULT_CLASH_MIXED_PORT = 7890`。
- 代理模式仍然是转发引擎占位，下一步接 tun2socks/用户态栈后才会真正联网转发。

## 下一步

优先实现：

1. Android 上游转发到 Windows Clash mixed-port。
2. 默认 SOCKS5，必要时支持 HTTP CONNECT。
3. 所有上游 socket 调用 `VpnService.protect()`，避免回环进入 Catch Report VPN。
4. UI 显示 Windows Clash 连通性测试。
