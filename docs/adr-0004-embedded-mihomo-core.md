# ADR-0004: 内置 Mihomo 完整代理核心

日期：2026-06-03

## 状态

已接入 Android 端；Windows 端已提供下载和启动脚本。

## 调研结论

近一年仍然活跃、适合复用的开源代理核心方向：

- MetaCubeX/mihomo：当前采用路线。`v1.19.26` 于 2026-05-31 发布，官方同时提供 Android 多 ABI 和 Windows amd64 产物。
- MetaCubeX/ClashMetaForAndroid：`v2.11.29` 于 2026-05-31 发布，项目把 Go 核心构建为 Android 原生库，证明“Android App 内置 Clash.Meta/mihomo 核心”是成熟路线。
- SagerNet/sing-box：`v1.13.12` 于 2026-05-15 发布，协议栈完整；但用户现有配置是 Clash/Mihomo 生态，因此本项目先选 mihomo。

## 决策

Catch Report 不再要求用户同时打开另一个 Android Clash VPN。主链路改为：

```text
目标 App
  -> Catch Report VpnService
  -> HEV tun2socks
  -> 127.0.0.1:7890
  -> 内置 Mihomo
  -> 用户订阅里的代理节点
  -> 出口网络
```

Android 系统层面仍然只有一个 VPN：Catch Report。Mihomo 只是 App 内的本地代理核心，不创建自己的 VPN/TUN。

## 实现约定

- Android 官方 mihomo core 由 `scripts/download-mihomo-cores.ps1` 下载到 `android/app/src/main/jniLibs/<abi>/libmihomo.so`。
- 官方 core 二进制不提交 Git，构建脚本会下载并校验 SHA256。
- App 支持导入本地 Clash/Mihomo YAML，也支持填写订阅 URL 后下载到 App 私有目录。
- 订阅 URL 只保存在本机 SharedPreferences，不写入 GitHub、不写入文档、不写入抓包 metadata。
- 导入或下载的配置会被规范化为只监听 `127.0.0.1:7890`，并移除顶层 `tun:`，避免 Mihomo 自己再开 VPN。
- App 通过 Mihomo `external-controller` 的 `/proxies` 接口读取代理组，通过 `PUT /proxies/{group}` 切换具体节点。
- 官方 `GeoIP.dat`、`GeoSite.dat`、`ASN.mmdb` 由构建脚本下载校验并打入 APK，避免首次启动时因 geodata 下载慢或第三方 geodata 不匹配而卡住。

## Windows

Windows 端保留 `pktmon` 作为抓包后端，并提供 Mihomo 辅助脚本：

```powershell
.\scripts\download-mihomo-windows.ps1
.\scripts\start-windows-mihomo.ps1 -ConfigFile D:\path\to\clash.yaml
.\scripts\start-windows-mihomo.ps1 -Stop
```

如果需要临时使用订阅：

```powershell
.\scripts\start-windows-mihomo.ps1 -SubscriptionUrl "<subscription-url>"
```

配置落在 `D:\Dev\Mihomo\windows\runtime\config.yaml`，不进入仓库。

## 当前限制

- 代理模式下 TUN fd 由 HEV native 引擎读取；当前已经能转发和写统计，但完整 PCAP tap 还需要加在 native 读包位置。
- 延迟测试、订阅更新周期、规则切换还没有 UI，需要后续通过 external-controller 或配置解析补齐。

## 来源

- https://github.com/MetaCubeX/mihomo/releases/tag/v1.19.26
- https://github.com/MetaCubeX/ClashMetaForAndroid/releases/tag/v2.11.29
- https://github.com/MetaCubeX/ClashMetaForAndroid
- https://github.com/SagerNet/sing-box/releases/tag/v1.13.12
