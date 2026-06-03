# 电脑端

第一阶段提供两个工具：

- `windows-pktmon/`: Windows 自带 `pktmon` 的抓包脚本封装。
- `pcap-viewer/`: 无依赖 PCAP/PCAPNG 本地查看器。
- `../scripts/start-windows-mihomo.ps1`: 下载并启动 Windows 版 Mihomo，用 Clash/Mihomo 配置或订阅作为本机代理出口。

## 推荐流程

1. 用管理员 PowerShell 启动抓包。
2. 停止抓包并转换为 PCAPNG。
3. 用 `pcap-viewer/index.html` 打开 PCAP/PCAPNG 文件分析。

如果需要先让电脑走自己的订阅代理：

```powershell
.\scripts\start-windows-mihomo.ps1 -ConfigFile D:\path\to\clash.yaml
```

停止 Mihomo：

```powershell
.\scripts\start-windows-mihomo.ps1 -Stop
```

后续阶段会接入 Npcap/libpcap，提供更完整的实时桌面应用。
