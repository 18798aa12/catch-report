# PCAP/PCAPNG 本地查看器

无依赖静态查看器，用浏览器打开 `index.html` 即可。

支持：

- PCAP little/big endian。
- 基础 PCAPNG Enhanced Packet Block。
- Ethernet 和 raw IP 链路类型。
- IPv4、IPv6、TCP、UDP、ICMP、DNS 查询摘要。
- 文件级统计、包列表、域名/IP/端口/字段值搜索、点击单包后的字段 / Value 和 hex/ASCII 预览。
- 明文 HTTP 会显示 method、URL、headers、query/form/json 字段；HTTPS 默认只能显示 TLS SNI/ALPN 等未加密握手字段。

限制：

- 这是第一阶段查看器，不替代 Wireshark。
- PCAPNG 只实现常见块和基础字段。
- 协议解析以测试分析为主，默认不尝试还原敏感内容。
