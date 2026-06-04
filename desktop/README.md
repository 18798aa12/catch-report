# 电脑端

第一阶段提供两个工具：

- `windows-pktmon/`: Windows 自带 `pktmon` 的抓包脚本封装。
- `windows-launcher/`: Windows EXE 启动器源码，双击后可按向导开始/停止抓包，并在 EXE 内搜索、分页、展开查看包详情。
- `pcap-viewer/`: 无依赖 PCAP/PCAPNG 本地查看器。
- `../scripts/start-windows-mihomo.ps1`: 下载并启动 Windows 版 Mihomo，用 Clash/Mihomo 配置或订阅作为本机代理出口。

## 推荐流程

1. 双击 `dist\CatchReport-Windows.exe`，按“不挂代理抓包”或“挂代理抓包”向导走。
2. 如果挂代理，先检测 Clash/Mihomo mixed/socks 入口，常见端口是 `7890` 或 `7891`。
3. 开始抓包，产生测试流量，停止后自动转换为 PCAPNG。
4. 在 EXE 的“结果”页搜索协议、IP、端口、域名、HTTP 字段、TLS SNI/ALPN 或字段 value。
5. 直接点击每个包展开详情，查看字段 / Value 和 RAW HEX/ASCII；也可以删除单个文件或一键清空。

也可以直接运行发布版：

```text
dist\CatchReport-Windows.exe
```

这个 EXE 带管理员运行声明，输出目录是：

```text
%USERPROFILE%\Documents\CatchReport\captures\desktop
```

如果需要先让电脑走自己的订阅代理：

```powershell
.\scripts\start-windows-mihomo.ps1 -ConfigFile D:\path\to\clash.yaml
```

停止 Mihomo：

```powershell
.\scripts\start-windows-mihomo.ps1 -Stop
```

当前 Windows 后端使用系统 `pktmon`，活动抓包写入 ETL，停止转换成 PCAPNG 后可稳定预览。后续阶段会接入 Npcap/libpcap，提供更细的网卡选择、loopback 和更完整的实时桌面抓包。
