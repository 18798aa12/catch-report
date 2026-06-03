package com.zhouqishun.catchreport

data class UpstreamProxyConfig(
    val enabled: Boolean = false,
    val type: Type = Type.SOCKS5,
    val host: String = "",
    val port: Int = 0
) {
    enum class Type {
        SOCKS5,
        HTTP_CONNECT
    }
}
