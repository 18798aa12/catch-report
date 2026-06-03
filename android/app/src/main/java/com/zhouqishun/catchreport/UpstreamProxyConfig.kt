package com.zhouqishun.catchreport

data class UpstreamProxyConfig(
    val enabled: Boolean = false,
    val type: Type = Type.SOCKS5,
    val host: String = "",
    val port: Int = DEFAULT_CLASH_MIXED_PORT
) {
    enum class Type {
        SOCKS5,
        HTTP_CONNECT
    }

    companion object {
        const val DEFAULT_CLASH_MIXED_PORT = 7890
    }
}
