# Windows PktMon 抓包入口

这是电脑端第一阶段抓包后端，使用 Windows 自带的 `pktmon`。

## 要求

- Windows 10/11。
- 管理员 PowerShell。

## 用法

查看状态：

```powershell
pwsh -File .\desktop\windows-pktmon\CatchReport.Desktop.ps1 -Action status
```

开始抓包：

```powershell
pwsh -File .\desktop\windows-pktmon\CatchReport.Desktop.ps1 -Action start
```

停止并转换为 PCAPNG：

```powershell
pwsh -File .\desktop\windows-pktmon\CatchReport.Desktop.ps1 -Action stop
```

按端口过滤：

```powershell
pwsh -File .\desktop\windows-pktmon\CatchReport.Desktop.ps1 -Action start -Port 443 -Protocol TCP
```

输出默认在：

```text
captures/desktop/
```

## 配合内置 Mihomo 出口

如果 Windows 也要一边走自己的代理一边抓包，可以先启动 Mihomo：

```powershell
.\scripts\start-windows-mihomo.ps1 -ConfigFile D:\path\to\clash.yaml
```

也可以临时传订阅 URL；脚本会把配置写到 `D:\Dev\Mihomo\windows\runtime\config.yaml`，不会写进仓库：

```powershell
.\scripts\start-windows-mihomo.ps1 -SubscriptionUrl "<subscription-url>"
```

脚本会同时下载并校验官方 geodata，规范化 `geox-url`，并把 Mihomo 限定在 `127.0.0.1` 本地监听。

然后再启动 `pktmon` 抓包。注意：如果目标软件只是走 Windows 系统代理，`pktmon --comp nics` 主要能看到本机到代理出口的网卡流量；后续接 Npcap 后会补 loopback 和更细的网卡选择。

## 已验证

2026-06-03 已用管理员 PowerShell 完成一次 smoke test：启动 `pktmon`、访问本地 HTTP 测试服务、执行 ICMP 流量、停止并转换为 PCAPNG。输出：

```text
captures/desktop/catch-report-20260603-211226.etl
captures/desktop/catch-report-20260603-211226.pcapng
```

## VPN 抓包提示

- 抓物理网卡：通常看到电脑到 VPN 服务器的加密隧道。
- 抓 VPN 虚拟网卡：有机会看到 VPN 内层流量，取决于 VPN 驱动是否暴露虚拟接口。

第一阶段使用 `pktmon --comp nics` 抓 NIC 组件。第二阶段接 Npcap 后会提供更细的网卡选择。
