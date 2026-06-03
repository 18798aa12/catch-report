# ADR-0003: Clash 使用习惯下的抓包方案

日期：2026-06-03

## 状态

已确定为兼容路线；主推荐已升级为内置 Mihomo 核心。

## 背景

用户在 Android 和 Windows 上通常都使用 Clash 进行代理。Clash/mihomo 类核心常见入站端口包括：

- `port`: HTTP(S) 代理端口，常见 `7890`。
- `socks-port`: SOCKS4/4a/5 代理端口，常见 `7891`。
- `mixed-port`: 混合端口，一个 TCP 端口同时支持 HTTP(S) 和 SOCKS5，很多配置常见 `7890`，部分客户端默认可能不同。

本项目 Android 代理链当前使用 `hev-socks5-tunnel`。主推荐是内置 Mihomo 加载 Clash/Mihomo 配置或订阅，并在 App 内监听 `127.0.0.1:7890`。外部 Clash 仍兼容：上游优先填 Clash `mixed-port = 7890`；如果所用客户端的 mixed 入口不接 SOCKS5，再改填 `socks-port = 7891`。

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

旧的外部 Clash 推荐：

```text
Android App 流量
  -> Catch Report VpnService
  -> PCAP 记录 / 流统计
  -> 用户态转发引擎
  -> Windows Clash mixed/socks-port
  -> Clash 代理出口
```

这样 Android 只运行 Catch Report 一个 VPN；Clash 运行在 Windows 上，作为 LAN 上游代理。

当前主推荐：

```text
Android App 流量
  -> Catch Report VpnService
  -> HEV tun2socks
  -> 127.0.0.1:7890
  -> 内置 Mihomo
  -> Clash/Mihomo 订阅节点
```

这种方式不需要 Android 另开 Clash VPN，也不需要 Windows Clash 开 Allow LAN。

## Android 本机 Clash 作为本地代理

如果必须让目标 App 看到 Android 正在挂 VPN，同时出口又必须走 Clash，可以使用这个链路：

```text
目标 App
  -> Catch Report VpnService
  -> PCAP 记录 / 流统计
  -> tun2socks 用户态转发
  -> 127.0.0.1:7890
  -> Android Clash 本地 mixed/socks-port
  -> Clash 代理出口
```

注意，这里 Android 系统仍然只有一个 VPN：Catch Report。Clash Android 不能再开 VPN/TUN，只能启动它的本地 mixed/SOCKS 代理能力；当前版本通过 SOCKS5 协议连接上游。

如果所用 Clash Android 客户端不支持“不开 VPN，只开本地代理服务”，则改用 Windows Clash 作为上游代理。

## 用户操作流程

使用内置 Mihomo 时，用户操作应是：

1. 打开 Catch Report。
2. 填写订阅链接并点击“下载订阅配置”，或导入本地 Clash/Mihomo YAML。
3. 点击“使用内置 Mihomo 代理”。
4. 点击“开始抓包”，同意 Android VPN 授权。
5. 打开目标软件。目标软件看到的是 Catch Report VPN，实际出口走 Mihomo 订阅节点。
6. 停止抓包后导出 `.pcap` / `.pcap.json`；代理模式当前写 `.pcap.pending.json` 和转发统计。

外部 Android Clash 作为本地代理时，仍可按旧流程：关闭 Clash VPN/TUN，只保留本地 mixed/SOCKS 代理，再在 Catch Report 中选择“使用安卓本机 Clash”。

## Windows Clash 需要打开 LAN 入站

为了让 Android 连接 Windows Clash，需要在 Windows Clash 客户端中确认：

1. Clash 正在监听 mixed-port，例如 `7890`；如果 mixed 不接 SOCKS5，使用 socks-port `7891`。
2. 开启 `allow-lan` 或 GUI 中的 Allow LAN / 允许局域网连接。
3. `bind-address` 允许局域网访问，例如 `*`、`0.0.0.0` 或 Windows 的局域网 IP。
4. Windows 防火墙允许该 TCP 端口的局域网入站。
5. Android 和 Windows 在同一个可信 Wi-Fi/LAN，且路由器没有开启客户端隔离。

示例配置：

```yaml
mixed-port: 7890
socks-port: 7891
allow-lan: true
bind-address: '*'
```

安全提醒：`allow-lan` 不是认证。只应在可信局域网打开，必要时用防火墙限制来源 IP。

模拟器测试 Windows Clash 时，如果 Clash 只监听 Windows `127.0.0.1`，可先建立端口反向转发：

```powershell
adb reverse tcp:7890 tcp:7890
```

然后在 Catch Report 中使用 `127.0.0.1:7890`。这等价于模拟 Android 本机有一个可用 SOCKS5/mixed 代理入口。

## 当前项目落地

- Android UI 的上游代理提示已改为 Clash mixed/socks 端口。
- `UpstreamProxyConfig.DEFAULT_CLASH_PROXY_PORT = 7890`。
- Android UI 已添加“使用安卓本机 Clash”和“使用 Windows Clash”预设。
- Android UI 已添加“使用内置 Mihomo 代理”“导入 Clash/Mihomo 配置”“下载订阅配置”。
- 代理模式已接入 `hev-socks5-tunnel` 转发引擎。
- 内置 Mihomo 通过官方 Android core 启动，配置保存到 App 私有目录。
- 下载/导入配置会强制本地 mixed-port 并移除顶层 `tun:`。
- 已补 `INTERNET` 权限，否则 native socks socket 无法创建。
- 代理模式已加入 HEV mapdns，VPN DNS 使用 `198.18.0.2`。
- 代理模式下 PCAP 文件仍待 native packet tap；当前会写 `.pcap.pending.json` 元数据和 tun2socks 统计。

## 本机验证

2026-06-03 在 Pixel6Api36 模拟器验证：

- `只记录 PCAP`：生成 `.pcap`，最终 smoke test 解析到 64 个包。
- `挂自己的代理再抓包`：已验证 `Catch Report VPN -> hev-socks5-tunnel -> 127.0.0.1:7891 -> adb reverse -> Windows Clash -> 192.168.1.6:18080`，测试 HTTP 服务命中请求。
- 内置 Mihomo 官方 x86_64 core 已在模拟器启动，并通过 HEV 转入 `127.0.0.1:7890`。
- Windows Clash mixed `7890` 作为外部代理备选继续保留；如果客户端 mixed 入口异常，保留 socks `7891` 作为手动备选。

## 下一步

优先实现：

1. 通过 Mihomo external-controller 做节点选择、延迟测试和订阅更新。
2. UI 显示内置 Mihomo / Android 本机 Clash / Windows Clash 连通性测试。
3. 在 HEV TUN 读包位置加 PCAP tap，让代理模式同时产出完整 PCAP。
