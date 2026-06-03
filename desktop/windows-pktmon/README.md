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

## VPN 抓包提示

- 抓物理网卡：通常看到电脑到 VPN 服务器的加密隧道。
- 抓 VPN 虚拟网卡：有机会看到 VPN 内层流量，取决于 VPN 驱动是否暴露虚拟接口。

第一阶段使用 `pktmon --comp nics` 抓 NIC 组件。第二阶段接 Npcap 后会提供更细的网卡选择。
